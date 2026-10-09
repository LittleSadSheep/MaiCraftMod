// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 靠近的组装：把站位判断、走向站位与走到拼成一个"带我靠近"的动作。
 *
 * <p>距离数值每刻从角色的属性读（交互距离被装备撑大后站位判断跟着变）；
 * 走过去的代价在寻路接缝上回答不了时按直线距离排——靠近只靠它挑先试哪个站位，
 * 走不走得到由真的走路决定。站位补救没有接上，候选站位全不过时如实以"到不了"收场。
 */
public final class LiveApproaches implements BringsPlayerClose {

    /** 服务端对床允许的距离档：站位与床底面中心各轴的偏移不超过它。 */
    private static final int BED_DISTANCE = 3;

    private final Supplier<PlayerContext> context;
    private final ApproachWorldView world;
    private final WalkTo walks;

    public LiveApproaches(Supplier<PlayerContext> context, ApproachWorldView world, WalkTo walks) {
        this.context = Objects.requireNonNull(context, "context");
        this.world = Objects.requireNonNull(world, "world");
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override
    public Action toward(InteractionTarget target, Permissions permissions) {
        // 每个靠近动作各走各的一份：路上能动多少地形按这次任务的许可来，暂停与收尾互不牵连。
        // 站位补救与保护格在此留空：受保护格不放行挖垫，能不能挖某一格由走到实现方的方块通行判断把关。
        return new Approach(target, reachNow(), world, LiveApproaches::straightCost,
                at -> false, new LiveSpotWalks(walks, TerrainPermit.of(permissions)), null, permissions);
    }

    /** 当刻的距离数值：交互距离读角色属性，眼高按当时姿态取。 */
    private ReachRules reachNow() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            // 没有角色上下文时给不出真实属性；按原版徒手触达给保底值，站位判断后面还会再核对。
            return new ReachRules(4.5, 3.0, BED_DISTANCE, 1.62);
        }
        var player = current.localPlayer();
        double blockRange = player.blockInteractionRange();
        double entityRange = player.entityInteractionRange();
        return new ReachRules(blockRange, entityRange, BED_DISTANCE, player.getEyeHeight());
    }

    /** 直线距离的代价：只用来给候选站位排序，走不走得到由真的走路决定。 */
    private static OptionalDouble straightCost(BlockPos from, BlockPos spot) {
        double dx = spot.getX() - from.getX();
        double dy = spot.getY() - from.getY();
        double dz = spot.getZ() - from.getZ();
        return OptionalDouble.of(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
