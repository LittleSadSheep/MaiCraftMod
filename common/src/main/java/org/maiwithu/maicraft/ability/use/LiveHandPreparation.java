// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 手上准备的生产实现：把要用的东西分刻换到主手，像真人一样先做界面搬运再选中快捷栏。
 *
 * <p>换东西交给换手读端（背包界面与副手交换的原生操作），它分刻推进、每步等游戏确认；
 * 空手就选中一个空着的快捷栏格。身上没有要拿的东西、或腾不出空手，给一个按缺物品
 * 失败的动作，缺的自己去拿是拿到物品模型的事。
 */
public final class LiveHandPreparation implements UseSeams.PreparesHand {

    private final ClientMovesToMainhand toMainhand;
    private final Supplier<PlayerContext> context;

    public LiveHandPreparation(ClientMovesToMainhand toMainhand, Supplier<PlayerContext> context) {
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<Action> hold(String item) {
        if (item == null) {
            return emptyHand();
        }
        if (!toMainhand.carried(item)) {
            return Optional.of(new Failing(Problem.of(Problem.Kind.NEED_ITEM,
                    "身上没有 " + item, "先拿到物品，再回来用")));
        }
        Optional<Action> move = toMainhand.actionToMainhand(item);
        if (move.isEmpty()) {
            PlayerContext current = context.get();
            // 换手给不出动作只有两种情况：已经在主手上（成了），或穿在盔甲格里（那不是换手的事）。
            if (current != null && current.localPlayer() != null && mainhandHolds(current.localPlayer(), item)) {
                return Optional.empty();
            }
            return Optional.of(new Failing(Problem.of(Problem.Kind.NEED_ITEM,
                    item + " 穿在身上，不是拿在手里的东西", "先脱下来或另找一件")));
        }
        return move;
    }

    // 要空手：主手已空就成了；不然选一个空快捷栏格切过去，一格都没有按腾不出交代。
    private Optional<Action> emptyHand() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return Optional.of(new Failing(Problem.of(Problem.Kind.WRONG_TIME,
                    "这一刻还掌握不到角色，腾不出空手", null)));
        }
        LocalPlayer player = current.localPlayer();
        if (player.getMainHandItem().isEmpty()) {
            return Optional.empty();
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return Optional.of(new SelectEmptySlot(slot));
            }
        }
        return Optional.of(new Failing(Problem.of(Problem.Kind.NEED_ITEM,
                "腾不出空手：快捷栏没有空格", "先丢掉或收起一件东西再试")));
    }

    private static boolean mainhandHolds(LocalPlayer player, String itemId) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return false;
        }
        return BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId);
    }

    /** 切到空快捷栏格的动作：选中是本地的按键动作，下一刻同步到服务端；选中后再核一遍主手。 */
    private record SelectEmptySlot(int slot) implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            if (player == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME,
                        "这一刻还掌握不到角色，腾不出空手", null));
            }
            if (!BaritoneInternals.ensureHotbarSelected(player.localPlayer(), slot)) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "选中快捷栏第 " + slot + " 格没成，腾不出空手", null));
            }
            return player.localPlayer().getMainHandItem().isEmpty()
                    ? ActionStatus.done()
                    : ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                            "切到空格后主手却不是空的，腾不出空手", null));
        }

        @Override
        public String describe() {
            return "切到空的快捷栏格";
        }
    }

    /** 立刻按给定的原因失败的动作：接缝需要返回动作，而失败原因在接缝这一侧才知道。 */
    private record Failing(Problem problem) implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(problem);
        }

        @Override
        public String describe() {
            return problem.message();
        }
    }
}
