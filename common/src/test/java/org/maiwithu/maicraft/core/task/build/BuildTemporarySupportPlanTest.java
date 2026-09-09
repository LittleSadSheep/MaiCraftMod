// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Covers dependency deferral, suspended roof support ordering, and receipt-owned cleanup. */
public final class BuildTemporarySupportPlanTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        deferredCellsDoNotStarveTheirSupport();
        suspendedRoofGetsRemovableSupport();
        protectedOrUnloadedTerrainIsNeverFilled();
        System.out.println("BuildTemporarySupportPlanTest: passed");
    }

    private static void deferredCellsDoNotStarveTheirSupport() {
        List<String> queue = List.of("overhang_a", "overhang_b", "wall");
        queue = BuildTemporarySupportPlan.defer(queue, 0, ignored -> true);
        check(queue.equals(List.of("overhang_b", "wall", "overhang_a")), "defer preserves untried work");
        queue = BuildTemporarySupportPlan.defer(queue, 0, ignored -> true);
        check(queue.getFirst().equals("wall"), "two overhangs must not hide the later permanent support");
        var lamp = new BuildTaskRecord.Target(Blocks.TORCH, Items.TORCH,
                new BlockPos(2, 65, 2), "interior light", null, null, null);
        var roof = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                new BlockPos(2, 67, 2), "roof", null, null, null);
        check(BuildOrder.BUILD_ORDER.compare(lamp, roof) < 0,
                "finish interior attachments on each floor before closing higher layers");
    }

    private static void suspendedRoofGetsRemovableSupport() {
        World world = new World();
        BlockPos roof = new BlockPos(0, 69, 0);
        var ledger = new BuildScaffoldLedger();
        var chain = BuildTemporarySupportPlan.find(world, pos -> true, roof, pos -> true);
        check(!chain.isEmpty() && chain.size() <= 7, "a floating roof finds a short column to the ground");
        check(chain.getLast().distManhattan(roof) == 1, "last support gives the roof a click face");
        var unique = new HashSet<>(chain);
        check(unique.size() == chain.size() && !unique.contains(roof), "helpers never overwrite the authored cell");
        check(ledger.isEmpty(), "a plan alone grants no cleanup ownership");
        for (BlockPos pos : chain) {
            boolean clickable = false;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                clickable |= world.getBlockState(neighbor).isFaceSturdy(world, neighbor, direction.getOpposite());
            }
            check(clickable, "each helper is supported by actual earlier work: " + pos);
            world.cells.put(pos, Blocks.DIRT.defaultBlockState());
            ledger.confirmed(pos, world.getBlockState(pos));
        }
        world.cells.put(roof, Blocks.OAK_PLANKS.defaultBlockState());
        for (BlockPos pos : chain.reversed()) {
            check(ledger.owns(pos, world.getBlockState(pos)), "cleanup requires confirmed unchanged support");
            world.cells.remove(pos); ledger.cleared(pos);
        }
        check(ledger.isEmpty() && world.getBlockState(roof).is(Blocks.OAK_PLANKS),
                "helper cleanup restores air and retains the completed permanent roof");
    }

    private static void protectedOrUnloadedTerrainIsNeverFilled() {
        World world = new World(); BlockPos target = new BlockPos(0, 69, 0);
        check(BuildTemporarySupportPlan.find(world, pos -> true, target, pos -> false).isEmpty(),
                "no route can cross a forbidden helper volume");
        check(BuildTemporarySupportPlan.find(world, pos -> pos.getY() > 63, target, pos -> true).isEmpty(),
                "unloaded ground cannot count as an anchor");
        check(BuildTemporarySupportPlan.find(world, pos -> true, new BlockPos(0, 150, 0), pos -> true).isEmpty(),
                "recovery is bounded rather than filling arbitrary height");
        var ledger = new BuildScaffoldLedger();
        check(ledger.permits(null, Blocks.AIR.defaultBlockState(), false), "unused exterior air permits supports");
        check(!ledger.permits(null, Blocks.AIR.defaultBlockState(), true), "inherited protection still wins");
        check(!ledger.permits(null, Blocks.STONE.defaultBlockState(), false), "unowned terrain is never a helper");
    }

    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        @Override public BlockState getBlockState(BlockPos pos) {
            return cells.getOrDefault(pos, pos.getY() <= 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
