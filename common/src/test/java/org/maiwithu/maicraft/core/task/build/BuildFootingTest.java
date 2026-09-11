// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

public final class BuildFootingTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        turnsAlongExistingWall();
        climbsPermanentStepBeforeOverheadPlacement();
        keepsAdjacentWallWorkAheadOfDistantCorners();
        budgetRetainsAlreadyProvenLowerFooting();
        System.out.println("BuildFootingTest: wall turns, permanent steps and local continuity passed");
    }

    private static void turnsAlongExistingWall() throws Exception {
        try (var h = world()) {
            for (int x = 3; x <= 7; x++) for (int y = 1; y <= 2; y++) h.set(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
            for (int z = 3; z <= 7; z++) for (int y = 1; y <= 2; y++) h.set(new BlockPos(7, y, z), Blocks.STONE.defaultBlockState());
            h.position(new Vec3(3.5, 3, 3.5));
            var target = stone(7, 3, 7); var destination = new BlockPos(7, 3, 6);
            var search = new BuildWorksitePlanner.Search(h.player, List.of(target), Map.of(),
                    destination::equals, LongSets.emptySet(), Set.of(), (t, g) -> true);
            var site = finish(search).best();
            check(site != null && site.heightLoss() == 0 && site.feet().y == 3,
                    "a wall turn must remain useful without descending to the floor");
            check(site.route().size() >= 7 && site.route().stream().allMatch(p -> p.y >= 3),
                    "the worksite retains a witnessed route around the corner, not a diagonal shortcut");
            check(h.blockUses() == 0, "search may not construct its own route");
        }
    }

    private static void climbsPermanentStepBeforeOverheadPlacement() throws Exception {
        try (var h = world()) {
            for (int z = 6; z <= 8; z++) h.set(new BlockPos(4, 1, z), Blocks.STONE.defaultBlockState());
            h.position(new Vec3(3.5, 1, 6.5));
            var target = stone(4, 2, 8);
            var search = new BuildWorksitePlanner.Search(h.player, List.of(target), Map.of(),
                    ignored -> true, LongSets.emptySet(), Set.of(), (t, g) -> true);
            var site = finish(search).best();
            check(site != null && site.feet().y == 2 && site.heightLoss() == 0,
                    "an existing step should beat a lower stance that reaches upward");
            check(site.route().stream().anyMatch(p -> p.y == 1) && site.route().stream().anyMatch(p -> p.y == 2),
                    "the raised stance must be connected by an existing one-block step");
            var task = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("footing-before-overhead", 1000, List.of(target), false, false));
            var type = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var constructor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            Object cell = constructor.newInstance(target, List.of());
            field("cell").set(task, cell); field("queue").set(task, new java.util.ArrayList<>(List.of(cell)));
            invoke(task, "placeNavTick");
            check(field("phase").get(task).toString().equals("WORKSITE"),
                    "current-position reach must not bypass the raised-footing search");
            check(h.blockUses() == 0, "choosing a better stance is still read-only");
        }
    }

    private static void keepsAdjacentWallWorkAheadOfDistantCorners() {
        var previous = new BlockPos(4, 2, 4);
        var adjacent = stone(5, 2, 4); var corner = stone(8, 2, 8);
        var targets = Map.of(adjacent.pos().asLong(), adjacent, corner.pos().asLong(), corner);
        var order = BuildPlacementPreference.targets(new Vec3(4.5, 3, 4.5), previous, targets);
        check(order.compare(adjacent, corner) < 0, "continue the adjacent wall before another distant corner");
    }

    private static void budgetRetainsAlreadyProvenLowerFooting() throws Exception {
        try (var h = world()) {
            h.position(new Vec3(3.5, 2, 4.5));
            BlockPos lower = new BlockPos(4, 1, 4);
            var view = new net.minecraft.world.level.BlockGetter() {
                public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                    return (pos.getY() == 0 || pos.getY() == 1 && !pos.equals(lower)
                            ? Blocks.STONE : Blocks.AIR).defaultBlockState();
                }
                public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
                public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) { return null; }
                public int getHeight() { return 16; }
                public int getMinBuildHeight() { return 0; }
            };
            var walking = new BuildSupportWalking(view, pos -> true, .6, 1.8, LongSets.emptySet(),
                    org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot.EMPTY);
            check(walking.edge(h.player.position(), Vec3.atBottomCenterOf(lower)),
                    "the adjacent one-block descent has an actually clear, supported route");
            var search = new BuildFootingSearch(h.player, List.of(stone(4, 1, 4)), LongSets.emptySet());
            var walkingField = BuildFootingSearch.class.getDeclaredField("walking"); walkingField.setAccessible(true);
            walkingField.set(search, walking);
            boolean complete = false;
            for (int n = 0; n < 20_000 && !(complete = search.advance()); n++) { }
            check(complete, "the footing graph must respect its finite expansion budget");
            var route = search.route(lower);
            check(route != null && route.points().size() == 2 && route.lowestY() == 1,
                    "preferring the wide upper floor must not discard an already proven adjacent descent");
            check(h.blockUses() == 0, "probing a lower fallback cannot construct a route");
        }
    }

    private static BuildWorksitePlanner.Progress finish(BuildWorksitePlanner.Search search) {
        for (int tick = 0; tick < 10_000; tick++) {
            var result = search.advance(256); if (result.complete()) return result;
        }
        throw new AssertionError("bounded worksite search did not finish");
    }
    private static InteractionWorldTestHarness world() throws Exception {
        var h = new InteractionWorldTestHarness();
        var dimensions = net.minecraft.world.entity.Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, net.minecraft.world.entity.EntityDimensions.scalable(.6F, 1.8F));
        return h;
    }
    private static BuildTaskRecord.Target stone(int x, int y, int z) {
        return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(x, y, z), "wall", null, null, null);
    }
    private static Field field(String name) throws Exception {
        var field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object invoke(FirstPersonBuildCompanionTask task, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
