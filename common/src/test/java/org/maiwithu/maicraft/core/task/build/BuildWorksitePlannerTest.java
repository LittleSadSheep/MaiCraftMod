// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
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
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Native world geometry: coverage, blocked views, exact facing and conservative walking support. */
public final class BuildWorksitePlannerTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            dimensions(h);
            h.position(new Vec3(2.5, 1, 6.5));
            List<BuildTaskRecord.Target> platform = new ArrayList<>();
            for (int x = 6; x <= 8; x++) for (int z = 5; z <= 7; z++) platform.add(stone(x, 1, z));
            BlockPos near = new BlockPos(3, 1, 6), better = new BlockPos(5, 1, 6);
            var search = search(h, platform, Set.of(near, better), Set.of());
            var emptyBudget = search.advance(0);
            check(!emptyBudget.complete() && emptyBudget.best() == null && emptyBudget.candidateChecks() == 0,
                    "a zero budget is pending work, not a no-path result");
            var found = finish(search).best();
            check(found != null && found.stance().equals(better) && found.coverage() == platform.size(),
                    "farther worksite must win by covering the entire platform in one stop");
            for (int i = 1; i < found.placements().size(); i++)
                check(BuildOrder.BUILD_ORDER.compare(found.placements().get(i - 1).target(),
                        found.placements().get(i).target()) <= 0, "covered targets retain stable dependency order");
            check(finish(search(h, platform, Set.of(near, better), Set.of(better))).best().stance().equals(near),
                    "a rejected worksite cannot be returned again");

            var forbidden = new LongOpenHashSet(); forbidden.add(better.above().asLong());
            check(finish(new BuildWorksitePlanner.Search(h.player, platform, Map.of(), Set.of(better)::contains,
                    forbidden, Set.of(), (t, g) -> true)).best() == null,
                    "a forbidden head cell excludes an otherwise useful stance");
            for (int z = 0; z < 16; z++) h.set(new BlockPos(4, 0, z), Blocks.AIR.defaultBlockState());
            check(finish(search(h, platform, Set.of(better), Set.of())).best() == null,
                    "a standable destination across an existing trench is not a walking corridor");
            for (int z = 0; z < 16; z++) h.set(new BlockPos(4, 0, z), Blocks.STONE.defaultBlockState());
            h.set(better.below(), Blocks.AIR.defaultBlockState());
            var futureFloor = stone(5, 0, 6);
            check(finish(new BuildWorksitePlanner.Search(h.player, platform,
                    Map.of(futureFloor.pos().asLong(), futureFloor), Set.of(better)::contains,
                    LongSets.emptySet(), Set.of(), (t, g) -> true)).best() == null,
                    "a planned floor cannot stand in for real support");
        }
        try (var h = new InteractionWorldTestHarness()) {
            dimensions(h);
            h.position(new Vec3(3.5, 1, 8.5));
            BlockPos blocked = new BlockPos(5, 1, 5), clear = new BlockPos(5, 1, 8);
            for (int z = 4; z <= 6; z++) for (int y = 1; y <= 3; y++)
                h.set(new BlockPos(6, y, z), Blocks.STONE.defaultBlockState());
            var cells = List.of(stone(7, 1, 7), stone(8, 1, 7));
            var found = finish(search(h, cells, Set.of(blocked, clear), Set.of())).best();
            check(found != null && found.stance().equals(clear) && found.coverage() == 2,
                    "occluded targets should move the worksite to the visible side of the wall");
        }
        try (var h = new InteractionWorldTestHarness()) {
            dimensions(h);
            var furnace = new BuildTaskRecord.Target(Blocks.FURNACE.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), Items.FURNACE,
                    new BlockPos(8, 1, 8), "exact east facing", Direction.EAST, null, null,
                    false, Set.of("facing"), true);
            var east = BuildPlacementGeometry.liveGestureFrom(h.player, furnace, Map.of(), new Vec3(10.5, 1, 8.5));
            var west = BuildPlacementGeometry.liveGestureFrom(h.player, furnace, Map.of(), new Vec3(6.5, 1, 8.5));
            check(east != null && west == null, "coverage must prove the authored state using the prospective yaw");
            check(BuildPlacementGeometry.liveGestureFrom(h.player, furnace, Map.of(), new Vec3(10.5, 1, 8.5),
                    ignored -> false) == null, "denied gestures cannot contribute to coverage");
            var rejected = new PlacementAttemptLedger(); rejected.rejectStance(furnace, new BlockPos(10, 1, 8));
            h.position(new Vec3(11.5, 1, 8.5));
            check(finish(new BuildWorksitePlanner.Search(h.player, List.of(furnace), Map.of(),
                    Set.of(new BlockPos(10, 1, 8))::contains, LongSets.emptySet(), Set.of(), rejected::allows)).best() == null,
                    "current-position and planned worksite rejection must share the same ledger");
        }
        BuildWorksiteSelectionTest.run();
        System.out.println("BuildWorksitePlannerTest: passed");
    }

    private static BuildWorksitePlanner.Search search(InteractionWorldTestHarness h,
            List<BuildTaskRecord.Target> targets, Set<BlockPos> allowed, Set<BlockPos> rejected) {
        return new BuildWorksitePlanner.Search(h.player, targets, Map.of(), allowed::contains,
                LongSets.emptySet(), rejected, (t, g) -> true);
    }
    private static void dimensions(InteractionWorldTestHarness h) throws Exception {
        var field = net.minecraft.world.entity.Entity.class.getDeclaredField("dimensions");
        field.setAccessible(true);
        field.set(h.player, net.minecraft.world.entity.EntityDimensions.scalable(.6F, 1.8F));
    }
    private static BuildWorksitePlanner.Progress finish(BuildWorksitePlanner.Search search) {
        for (int tick = 0; tick < 10_000; tick++) {
            var progress = search.advance(256);
            if (progress.complete()) return progress;
        }
        throw new AssertionError("finite candidate search did not complete");
    }
    private static BuildTaskRecord.Target stone(int x, int y, int z) {
        return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(x, y, z), "platform", null, null, null);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
