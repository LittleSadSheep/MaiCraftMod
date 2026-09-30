// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** 头顶挡住起飞时先走到附近有净空的地面，不让模型手动分拆出棚与飞行。 */
public final class JetpackDepartureApproach {
    private JetpackDepartureApproach() {}

    public static PlayerNav create(LocalPlayerContext context, PlayerNav.ContextProvider policy,
                                   GoalCompiler.Compiled destination, Set<Long> rejected) {
        var player = context.player();
        var power = JetpackNativeAdapter.inspect(context);
        if (!power.controllable() || !player.onGround() || player.isInWater() || player.isPassenger()) return null;
        var space = JetpackRoute.observed(context, policy.embeddedForbiddenBodyCells());
        Vec3 start = player.position();
        var candidates = new LinkedHashSet<BlockPos>();
        // 只找当前高度附近六格内已加载的地面；起飞空间仍按原生推力余量核对，不拆屋顶来制造净空。
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            Vec3 probe = new Vec3(Math.floor(start.x) + dx + .5, start.y + 1.1, Math.floor(start.z) + dz + .5);
            Vec3 feet = space.landingBelow(probe);
            if (feet == null || Math.abs(feet.y - start.y) > 1.01 || feet.distanceToSqr(start) < .75
                    || Math.abs(feet.y - Math.rint(feet.y)) > .02 || !clear(space, feet, power)) continue;
            BlockPos at = BlockPos.containing(feet);
            if (!rejected.contains(at.asLong())) candidates.add(at);
        }
        var stances = candidates.stream().sorted(Comparator.comparingDouble(at -> at.getCenter().distanceToSqr(start))).limit(64).toList();
        if (stances.isEmpty()) return null;
        var goal = new GoalCompiler.Compiled(NavGoal.composite(stances.stream().map(NavGoal::exact).toList()), destination.sacred());
        // 地面准备沿用原地形许可和保护格，只走路；到场再以实际身体位置复核离地空间，避免递归启动飞行。
        return PlayerNav.to(player, () -> goal, 1.0, () -> {
            var fresh = ClientRuntime.requireContext(player);
            return player.onGround() && clear(JetpackRoute.observed(fresh, policy.embeddedForbiddenBodyCells()),
                    player.position(), JetpackNativeAdapter.inspect(fresh));
        }, policy).walkingOnly();
    }

    private static boolean clear(JetpackRoute.Space space, Vec3 feet, JetpackNativeAdapter.Snapshot power) {
        if (!power.controllable()) return false;
        // 与飞行搜索的高、低两种起步高度一致，不能仅凭角色能站着就认定可以开启推力。
        Vec3 high = new Vec3(Math.floor(feet.x) + .5, Math.ceil(feet.y) + 1, Math.floor(feet.z) + .5);
        return JetpackRoute.flightClear(space, feet, high, power)
                || JetpackRoute.flightClear(space, feet, high.add(0, -1, 0), power);
    }
}
