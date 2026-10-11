// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用配置器改一面的侧面配置，一次往后挪一格：拿到配置器 → 换到主手 → 按换模式键调到那种介质的模式 →
 * 潜行右键那一面，那一面的模式变了才算点上。改成要的值要点几下由 machine_configure 逐项读回、循环点，
 * 这里一次只点一下，不替它猜要点几次。
 */
final class ConfiguratorClicks {

    static final String CONFIGURATOR = "mekanism:configurator";
    /** 配置器一共八种模式：拨满一圈还没到要的模式就是这件配置器不认，如实说。 */
    private static final int MODES = 8;
    /** 拨一下等物品上的模式变过来的期限（刻）。 */
    private static final int MODE_WAIT_TICKS = 20;

    private final MekanismCompat compat;
    private final PlayerServices services;

    ConfiguratorClicks(MekanismCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 改一下 at 这台机器 side 那一面的 medium。 */
    Action once(BlockPos at, SideSettings.Key key, Permissions permissions) {
        String mode = SideSettings.configuratorMode(key.medium());
        return new StepsInOrder("用配置器改 " + key.side().getName() + " 面的" + SideSettings.mediumName(key.medium()),
                List.of(new StepsInOrder.Step("拿配置器", () -> services.needs().actionFor(
                                new ItemRequest(WantedItem.ofItem(CONFIGURATOR), 1, "改 Mekanism 侧面配置"), permissions)),
                        new StepsInOrder.Step("配置器换到主手",
                                () -> holding() ? null : services.toMainhand().moveToMainhand(CONFIGURATOR).orElse(null)),
                        new StepsInOrder.Step("调到" + SideSettings.mediumName(key.medium()) + "模式",
                                () -> new ModeSwitch(mode)),
                        new StepsInOrder.Step("潜行右键那一面", () -> click(at, key, permissions))));
    }

    // 潜行右键那一面：那一面这种介质的模式和点之前不一样了才算点上。
    private Action click(BlockPos at, SideSettings.Key key, Permissions permissions) {
        String before = current(at, key).orElse("");
        return services.sneak().sneaking(new HandThenClick(services, "配置器点 " + key.side().getName() + " 面", at,
                PlacementSpots.faceBox(at, key.side()), permissions, Optional::empty,
                () -> context -> current(at, key).map(now -> !now.equals(before)).orElse(false)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING));
    }

    private Optional<String> current(BlockPos at, SideSettings.Key key) {
        PlayerContext player = services.context().get();
        ClientLevel level = player == null ? null : player.level();
        if (level == null) return Optional.empty();
        return compat.sideConfig(level, at).map(config -> config.sides().get(key.medium()))
                .map(sides -> sides.get(key.side()));
    }

    private boolean holding() {
        PlayerContext player = services.context().get();
        if (player == null || player.localPlayer() == null) return false;
        ItemStack held = player.localPlayer().getMainHandItem();
        return !held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(CONFIGURATOR);
    }

    /** 按换模式键一格一格拨：物品上的模式变过来再拨下一下，到要的模式为止；拨满一圈还不对就停。 */
    private final class ModeSwitch implements Action {
        private final String wanted;
        private String lastSeen;
        private int presses;
        private int waited;

        ModeSwitch(String wanted) {
            this.wanted = wanted;
        }

        @Override public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            if (player == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，调不了配置器"));
            }
            Optional<String> mode = compat.configuratorMode(player.localPlayer().getMainHandItem());
            if (mode.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "主手拿的不是配置器"));
            }
            if (mode.get().equals(wanted)) return ActionStatus.done();
            // 上一下拨出去了还没变过来：再等一会儿，不连按，免得拨过头。
            if (mode.get().equals(lastSeen) && ++waited < MODE_WAIT_TICKS) return ActionStatus.running();
            if (presses >= MODES) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "配置器拨了一圈也没调到 " + wanted + " 模式"));
            }
            compat.nextConfiguratorMode();
            presses++;
            waited = 0;
            lastSeen = mode.get();
            return ActionStatus.progressed();
        }

        @Override public String describe() {
            return "把配置器调到 " + wanted + " 模式";
        }
    }
}
