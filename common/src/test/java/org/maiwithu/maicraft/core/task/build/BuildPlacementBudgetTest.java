// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Native placement enumeration yields inside a failed target instead of freezing a whole client tick. */
public final class BuildPlacementBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos pos = new BlockPos(8, 1, 8);
            var impossible = new BuildTaskRecord.Target(Blocks.RESPAWN_ANCHOR.defaultBlockState()
                    .setValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES, 4), Items.RESPAWN_ANCHOR,
                    pos, "unproducible native state", null, null, null, false, Set.of("charges"), true);
            var search = new BuildPlacementGeometry.PlanSearch(h.player, impossible, Map.of());
            int reads = h.level.blockReads;
            var previous = search.advance(0);
            check(!previous.complete() && previous.probeCount() == 0 && h.level.blockReads == reads,
                    "zero budget observes no world and cannot assert absence of a gesture");
            try { search.results(); throw new AssertionError("unfinished enumeration exposed a final empty result"); }
            catch (IllegalStateException expected) { }
            int slices = 0;
            while (!previous.complete() && slices++ < 10_000) {
                reads = h.level.blockReads;
                var next = search.advance(1);
                check(next.probeCount() - previous.probeCount() <= 1
                                && next.stanceChecks() - previous.stanceChecks() <= 1,
                        "one unit of work cannot hide an entire stance or target enumeration");
                check(h.level.blockReads - reads <= 256,
                        "a bounded native probe in this simple world cannot scan thousands of cells");
                if (!next.complete()) check(next.gestureCount() == 0,
                        "the requested charge value cannot be fabricated while the search is pending");
                previous = next;
            }
            check(previous.complete() && search.results().isEmpty(),
                    "a negative answer becomes final only when all supports and stances were exhausted");
            check(previous.stanceChecks() == BuildPlacementGeometry.candidateStances(pos).size()
                            && previous.probeCount() > 100 && slices > 100,
                    "the difficult negative target must really be spread across many independent slices");

            BlockPos stance = pos.west();
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, pos, "supported block", null, null, null);
            h.position(new net.minecraft.world.phys.Vec3(6.5, 1, 8.5));
            reads = h.level.blockReads;
            var accepted = BuildPlacementGeometry.currentGesture(h.player, target, Map.of());
            int acceptedReads = h.level.blockReads - reads;
            var deniedPoints = new java.util.concurrent.atomic.AtomicInteger();
            reads = h.level.blockReads;
            var denied = BuildPlacementGeometry.currentGesture(h.player, target, Map.of(), g -> {
                deniedPoints.incrementAndGet(); return false;
            });
            int deniedReads = h.level.blockReads - reads;
            check(accepted != null && denied == null && deniedPoints.get() > 0,
                    "the same reachable points must be rejected before expensive native proof");
            check(deniedReads < acceptedReads,
                    "rejecting all points must skip ray/world/prediction reads rather than filter a completed candidate list");
            var allowed = Set.of(stance);
            var positive = new BuildPlacementGeometry.PlanSearch(h.player, target, Map.of(), false, false, allowed::contains);
            var done = finish(positive, 7);
            List<BuildPlacementGeometry.Gesture> results = positive.results();
            check(!results.isEmpty() && results.stream().allMatch(g -> g.stance().equals(stance)),
                    "resuming a search preserves its approved stance and all valid gestures");
            int topSamples = BuildPlacementGeometry.facePoints(pos.below(),
                    h.level.getBlockState(pos.below()).getShape(h.level, pos.below()), Direction.UP).size();
            check(done.probeCount() == topSamples,
                    "one approved stance probes every actual top-face sample exactly once");
            h.set(pos.below(), Blocks.AIR.defaultBlockState());
            h.set(pos.east(), Blocks.STONE.defaultBlockState());
            var side = new BuildPlacementGeometry.PlanSearch(h.player, target, Map.of(), false, false, allowed::contains);
            var sideDone = finish(side, 5);
            int sideSamples = BuildPlacementGeometry.facePoints(pos.east(),
                    h.level.getBlockState(pos.east()).getShape(h.level, pos.east()), Direction.WEST).size();
            check(sideDone.probeCount() == sideSamples && sideSamples == 12,
                    "cursor lengths must follow horizontal-face samples rather than assume nine points");
        }
        System.out.println("BuildPlacementBudgetTest: finite positive and negative searches yield per face probe");
    }

    private static BuildPlacementGeometry.PlanProgress finish(BuildPlacementGeometry.PlanSearch search, int budget) {
        var progress = search.advance(0);
        for (int slice = 0; slice < 10_000 && !progress.complete(); slice++) progress = search.advance(budget);
        check(progress.complete(), "a finite search must eventually complete"); return progress;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
