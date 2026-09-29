// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;

/** 贴边放置必须同时证明完成后的退路；新增轴、横梁或门另一半不能封住原先用来站立的锚点。 */
final class BuildPlacementReturnGeometry {
    private BuildPlacementReturnGeometry() {}
    static boolean allowed(LocalPlayer player, BuildTaskRecord.Target target, BuildSupportWorld before,
            Predicate<BlockPos> loaded, LongSet forbidden, PhysicalObstacleSnapshot physical, Vec3 edge, Vec3 anchor) {
        var placed = new LinkedHashMap<BlockPos, BlockState>();
        placed.put(target.pos(), target.desiredState());
        BuildPlacementGeometry.generatedBy(target).forEach(cell -> placed.put(cell.pos(), cell.expected()));
        // 包住已有支撑投影再叠本次放置，不能用新图层抹掉先前已经证明的脚手架前缀。
        var after = new BuildSupportWorld(before, loaded, placed);
        double width = player.getBbWidth();
        // 楼梯高半面也能原生承托身体；沿用连续支撑证明，不能额外要求脚底每一点都铺满整方块。
        var crouching = new GroundCorridor(after, loaded, width, player.getDimensions(Pose.CROUCHING).height(), forbidden, physical);
        if (!crouching.clear(edge, edge) || !crouching.clear(edge, anchor)) return false;
        // 回到锚点后会交还普通导航，除了潜行能穿过，落点本身也要容纳正常站姿。
        var standing = new GroundCorridor(after, loaded, width, player.getDimensions(Pose.STANDING).height(), forbidden, physical);
        return standing.clear(anchor, anchor);
    }
}
