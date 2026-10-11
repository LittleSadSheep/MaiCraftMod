// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;

/**
 * 链条输送机之间的一条链：两台链条输送机，手持锁链先右键一台、再右键另一台；
 * 第一下只记在客户端，第二下 Create 才发给服务器，扣掉按长度算的锁链、在两台上都记下这条连接。
 *
 * <p>形状规则按 Create 自己的：两台距离小于服务器配置的最长长度、不近于两格半，不能竖直连，坡度不超过一比一。
 * 第一下没有看得见的痕迹，只有第二下之后两台上都出现这条连接才算装上；第二下没出现就如实交回，不再重点。
 */
final class ChainConveyorInstallation implements CreateInstallation {

    static final String KIND = "create:chain_conveyor";
    private static final String CHAIN = "minecraft:chain";
    /** Create 不许两台挨得太近：距离要不近于两格半。 */
    private static final double MIN_DISTANCE = 2.5;
    /** 坡度按水平距离减一格半来算（两台各占半格多）。 */
    private static final double HORIZONTAL_ALLOWANCE = 1.5;

    private final CreateCompat compat;
    private final PlayerServices services;

    ChainConveyorInstallation(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String kind() {
        return KIND;
    }

    @Override public Optional<String> problem(Installation installation) {
        Optional<Integer> limit = services.context().get() == null ? Optional.empty()
                : Optional.of(compat.chainMaxLength());
        return shapeProblem(installation.cells(), limit);
    }

    /**
     * 一条链的形状合不合 Create 的规则，纯函数。
     *
     * @param cells     两台链条输送机所在的格
     * @param maxLength 服务器配置的最长长度；读不到（不在世界里）为空，这时不查长度
     */
    static Optional<String> shapeProblem(List<BlockPos> cells, Optional<Integer> maxLength) {
        if (cells.size() != 2) return Optional.of("一条链只连两台链条输送机：cells 写两端两格");
        BlockPos from = cells.get(0);
        BlockPos to = cells.get(1);
        double distance = Math.sqrt(from.distSqr(to));
        if (maxLength.isPresent() && distance >= maxLength.get()) {
            return Optional.of("两台相距 " + CreateReadings.round(distance) + " 格，超过服务器配置的最长 "
                    + maxLength.get() + " 格（要小于它）");
        }
        if (distance < MIN_DISTANCE) return Optional.of("两台挨得太近：Create 要两台相距不少于两格半");
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz) - HORIZONTAL_ALLOWANCE;
        if (horizontal <= 0) return Optional.of("链条不能竖直连：两台要在水平方向上错开");
        if (Math.abs(to.getY() - from.getY()) / horizontal > 1) {
            return Optional.of("太陡了：高差不能超过水平距离（减去一格半）");
        }
        return Optional.empty();
    }

    @Override public Action install(Installation installation, Permissions permissions) {
        BlockPos from = installation.cells().get(0);
        BlockPos to = installation.cells().get(1);
        int chains = compat.chainCost(to.subtract(from));
        // 拿够锁链（创造模式也要手里有一根）→ 点第一台 → 点第二台；第二下之后两台都记下这条连接才算装上。
        return new StepsInOrder("连链条 " + from.toShortString() + " → " + to.toShortString(), List.of(
                new StepsInOrder.Step("拿 " + chains + " 根锁链", () -> services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(CHAIN), chains, "连链条输送机"), permissions)),
                new StepsInOrder.Step("点第一台", () -> clickFirst(from, permissions)),
                new StepsInOrder.Step("点第二台", () -> clickSecond(installation, permissions))));
    }

    @Override public boolean installed(ClientLevel level, Installation installation) {
        if (installation.cells().size() != 2) return false;
        BlockPos from = installation.cells().get(0);
        BlockPos to = installation.cells().get(1);
        return linked(compat.chainConnections(level, from), compat.chainConnections(level, to), from, to);
    }

    /** 两台上都记着这条连接才算连上（Create 两头各记一份相对位置），纯函数。 */
    static boolean linked(Optional<Set<BlockPos>> fromLinks, Optional<Set<BlockPos>> toLinks, BlockPos from,
            BlockPos to) {
        return fromLinks.map(links -> links.contains(to.subtract(from))).orElse(false)
                && toLinks.map(links -> links.contains(from.subtract(to))).orElse(false);
    }

    // 第一下只在客户端记下起点，没有看得见的痕迹：游戏接下这一下右键就算点上，等第二下见分晓。
    private Action clickFirst(BlockPos from, Permissions permissions) {
        return new HandThenClick(services, "用锁链点第一台链条输送机", from, new AABB(from), permissions,
                this::chainToHand, () -> context -> InteractionConfirmation.Verdict.APPLIED);
    }

    // 第二下：两台上都出现这条连接才算装上；没出现（起点没记上、锁链不够、Create 不认）如实交回。
    private Action clickSecond(Installation installation, Permissions permissions) {
        BlockPos to = installation.cells().get(1);
        return new HandThenClick(services, "用锁链点第二台链条输送机", to, new AABB(to), permissions,
                this::chainToHand,
                () -> context -> installed(context.level(), installation)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    private Optional<Action> chainToHand() {
        PlayerContext current = services.context().get();
        if (current != null && current.localPlayer() != null) {
            ItemStack held = current.localPlayer().getMainHandItem();
            if (!held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(CHAIN)) {
                return Optional.empty();
            }
        }
        return services.toMainhand().moveToMainhand(CHAIN);
    }
}
