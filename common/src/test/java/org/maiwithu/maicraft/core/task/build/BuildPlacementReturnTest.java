// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;

/** 用原生细横杆复现“边缘能放、放完堵头”：预测完成后的回程，不实际移动玩家或预放方块。 */
public final class BuildPlacementReturnTest {
    private static final Vec3 ANCHOR = new Vec3(3.5, 1, 3.5), EDGE = ANCHOR.add(0, 0, -.65);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        placementMustLeaveReturnSpace(); earlierSupportProjectionIsPreserved(); alternateExitStaysWithinRealTerrain();
        System.out.println("BuildPlacementReturnTest: passed");
    }
    private static void placementMustLeaveReturnSpace() throws Exception {
        try (var h = fixture()) {
            var target = rod(2); var before = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
            check(new GroundCorridor(before, h.level::isLoaded, .6, 1.5, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY).clear(EDGE, ANCHOR),
                    "before placement the original crouching return is genuinely open");
            check(BuildPlacementGeometry.projectedGestureFrom(h.player, target, before, h.level::isLoaded, EDGE, true) != null,
                    "the thin horizontal block can be natively placed from the edge");
            check(!allowed(h, target, before), "placing the beam above the anchor must invalidate the projected return before clicking");
            var search = new BuildPlacementAccessSearch(h.player, target, before, ANCHOR, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY, 512, true);
            for (int i = 0; i < 4096 && !search.advance(16); i++) { }
            check(!search.accepted() && search.rejectedReturns() > 0,
                    "real placement-access search rejects an apparently reachable gesture that seals its only return anchor: " + search.reason());
            check(h.level.getBlockState(target.pos()).isAir() && h.inventory.getItem(0).getCount() == 16
                    && h.player.position().equals(ANCHOR) && h.blockUses() == 0, "return proof is read only and cannot fake construction or movement");
            // 横杆提高一格后，放置与退回都具备净空；不能把所有檐边或细杆都一概拒绝。
            check(allowed(h, rod(3), before), "a higher beam preserves a complete supported return and normal standing space");
        }
    }
    private static void earlierSupportProjectionIsPreserved() throws Exception {
        try (var h = fixture()) {
            BlockPos floor = new BlockPos(3, 0, 3); h.set(floor, Blocks.AIR.defaultBlockState());
            var before = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of(floor, Blocks.COBBLESTONE.defaultBlockState()));
            check(allowed(h, rod(3), before), "adding the placement projection must retain an already-proven support prefix");
            check(h.level.getBlockState(floor).isAir(), "projected support remains a planning fact rather than a world write");
        }
    }
    private static boolean allowed(InteractionWorldTestHarness h, BuildTaskRecord.Target target, BuildSupportWorld before) {
        return BuildPlacementReturnGeometry.allowed(h.player, target, before, h.level::isLoaded, LongSets.emptySet(),
                PhysicalObstacleSnapshot.EMPTY, EDGE, ANCHOR);
    }
    private static void alternateExitStaysWithinRealTerrain() throws Exception {
        try (var h = fixture()) {
            h.position(EDGE); h.set(new BlockPos(3, 2, 3), rod(2).desiredState());
            var isolated = recovery(h, LongSets.emptySet()); finish(isolated);
            check(!isolated.found(), "an isolated pillar cannot invent another supported exit under the new beam");
            // 西侧真有现成地板时才可绕过横杆；保持薄杆与所有材料，恢复搜索没有世界写入。
            h.set(new BlockPos(2, 0, 3), Blocks.COBBLESTONE.defaultBlockState());
            h.set(new BlockPos(2, 2, 3), Blocks.AIR.defaultBlockState());
            var connected = recovery(h, LongSets.emptySet()); finish(connected);
            check(connected.found() && connected.route().size() > 1 && h.blockUses() == 0,
                    "the side floor supplies a separate existing exit with an explicit continuous route");
            var forbidden = new LongOpenHashSet();
            forbidden.add(new BlockPos(2, 1, 3).asLong()); forbidden.add(new BlockPos(2, 1, 2).asLong());
            var blocked = recovery(h, forbidden); finish(blocked);
            check(!blocked.found(), "protected body cells cannot be crossed to fabricate an alternate exit");
            check(BuildEdgeRecovery.recoverable("placement_return_obstructed_before_click")
                    && !BuildEdgeRecovery.recoverable("edge_body_or_control_changed"),
                    "local geometry may replan while loss of body ownership cannot be retried as a route issue");
        }
    }
    private static BuildEdgeRecoverySearch recovery(InteractionWorldTestHarness h, LongSet forbidden) {
        return new BuildEdgeRecoverySearch(h.player, new BuildSupportWorld(h.level, h.level::isLoaded, Map.of()),
                forbidden, pos -> !forbidden.contains(pos.asLong()), PhysicalObstacleSnapshot.EMPTY);
    }
    private static void finish(BuildEdgeRecoverySearch search) {
        for (int i = 0; i < 4096; i++) if (search.advance()) return;
        throw new AssertionError("local recovery search did not finish its bounded geometry work");
    }
    private static BuildTaskRecord.Target rod(int y) {
        return new BuildTaskRecord.Target(Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST),
                Items.END_ROD, new BlockPos(3, y, 3), "thin beam above return anchor", null, null, null);
    }
    private static InteractionWorldTestHarness fixture() throws Exception {
        var h = new InteractionWorldTestHarness(); var dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F)); h.position(ANCHOR); h.player.setDeltaMovement(Vec3.ZERO);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.set(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
        h.set(new BlockPos(3, 0, 3), Blocks.COBBLESTONE.defaultBlockState());
        h.set(new BlockPos(2, 2, 3), Blocks.STONE.defaultBlockState());
        h.inventory.setItem(0, new ItemStack(Items.END_ROD, 16)); return h;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
