// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** 只有投影目标仍保留可达且形状真实的点击面时，支撑方块才有用。 */
public final class BuildSupportAccessTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        acceptsUsefulSupportAndInvalidatesChanges();
        rejectsVisibleButUnreachableStance();
        existingStepsRemainWalkable();
        projectedPredictionKeepsNativeDestinations();
        completedTargetsReleaseSupportOrdering();
        System.out.println("BuildSupportAccessTest: projected access, no-mutation rejection and existing footing passed");
    }

    private static void acceptsUsefulSupportAndInvalidatesChanges() throws Exception {
        try (var h = world()) {
            h.position(new Vec3(3.5, 1, 6.5));
            var target = stone(6, 2, 6); var below = target.pos().below();
            check(BuildPlacementGeometry.liveGestureFrom(h.player, target, Map.of(), h.player.position()) == null,
                    "the real world initially has no adjacent click support");
            var proof = search(h, target, List.of(below)); finish(proof);
            check(proof.accepted() && proof.witness().clicked().equals(below) && proof.witness().face() == Direction.UP,
                    "a projected support with a usable upper face should pass");
            check(proof.current() && h.level.getBlockState(below).isAir() && h.blockUses() == 0,
                    "accepted geometry remains a read-only prediction");
            h.set(below, Blocks.STONE.defaultBlockState());
            check(!proof.current(), "a support site changed after planning invalidates the permission to place it");
            var denied = new BuildSupportAccess(h.player, target, List.of(below.east()), Blocks.DIRT.defaultBlockState(),
                    ignored -> false, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
            finish(denied); check(!denied.accepted(), "protected support positions must fail before mutation");
        }
    }

    private static void rejectsVisibleButUnreachableStance() throws Exception {
        try (var h = world()) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.set(new BlockPos(x, 0, z), Blocks.AIR.defaultBlockState());
            h.set(new BlockPos(3, 0, 6), Blocks.STONE.defaultBlockState());
            h.set(new BlockPos(5, 1, 6), Blocks.STONE.defaultBlockState());
            h.set(new BlockPos(6, 1, 6), Blocks.STONE.defaultBlockState());
            h.position(new Vec3(3.5, 1, 6.5));
            var target = stone(6, 3, 6); var below = target.pos().below();
            var projected = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of(below, Blocks.DIRT.defaultBlockState()));
            check(BuildPlacementGeometry.projectedGestureFrom(h.player, target, projected, h.level::isLoaded,
                    new Vec3(5.5, 2, 6.5)) != null, "a distant platform has a geometric placement opportunity");
            var proof = search(h, target, List.of(below)); finish(proof);
            check(!proof.accepted(), "a floating stance across missing floor is not a reachable proof");
            check(h.blockUses() == 0 && h.level.getBlockState(below).isAir(), "access search never builds its own unapproved bridge");
        }
    }

    private static void existingStepsRemainWalkable() throws Exception {
        try (var h = world()) {
            h.set(new BlockPos(4, 1, 6), Blocks.STONE.defaultBlockState());
            var walking = new BuildSupportWalking(h.level, h.level::isLoaded, .6, 1.8,
                    LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
            Vec3 from = new Vec3(3.5, 1, 6.5), to = walking.stance(new BlockPos(4, 2, 6));
            check(to != null && walking.edge(from, to), "existing one-block steps do not need new scaffolding");
            h.set(new BlockPos(3, 3, 6), Blocks.STONE.defaultBlockState());
            check(!walking.edge(from, to), "the body cannot jump through a low ceiling");
        }
    }

    private static InteractionWorldTestHarness world() throws Exception {
        var h = new InteractionWorldTestHarness();
        h.player.setDeltaMovement(Vec3.ZERO);
        var dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        return h;
    }

    private static void projectedPredictionKeepsNativeDestinations() throws Exception {
        try (var h = world()) {
            h.position(new Vec3(5.5, 1, 7.5));
            var slab = new BuildTaskRecord.Target(Blocks.OAK_SLAB, Items.OAK_SLAB,
                    new BlockPos(6, 2, 6), "upper slab", null, null, null);
            h.set(slab.pos().below(), Blocks.OAK_SLAB.defaultBlockState());
            var projected = new BuildSupportWorld(h.level, h.level::isLoaded,
                    Map.of(new BlockPos(10, 1, 10), Blocks.DIRT.defaultBlockState()));
            check(BuildPlacementGeometry.projectedGestureFrom(h.player, slab, projected, h.level::isLoaded,
                    h.player.position()) == null, "existing slabs retain native merge destinations in projected checks");
            var north = Blocks.RED_BED.defaultBlockState().setValue(
                    BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            var bed = new BuildTaskRecord.Target(north, Items.RED_BED, new BlockPos(8, 2, 8), "bed", null, null, null);
            var stage = new BuildPlacementStage(projected, h.level::isLoaded, Map.of(), bed, false, true);
            var method = BuildPlacementGeometry.class.getDeclaredMethod("projectedPlacementClear", LocalPlayer.class,
                    BuildTaskRecord.Target.class, BuildPlacementStage.class, Vec3.class, BlockState.class);
            method.setAccessible(true);
            check(!(boolean) method.invoke(null, h.player, bed, stage, h.player.position(), north.setValue(
                    BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)),
                    "a projected bed may not put its generated head outside the declared footprint");
        }
    }
    private static BuildSupportAccess search(InteractionWorldTestHarness h, BuildTaskRecord.Target target, List<BlockPos> chain) {
        return new BuildSupportAccess(h.player, target, chain, Blocks.DIRT.defaultBlockState(), ignored -> true,
                LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
    }

    private static void completedTargetsReleaseSupportOrdering() throws Exception {
        try (var h = world()) {
            h.position(new Vec3(3.5, 1, 6.5)); h.inventory.setItem(0, new ItemStack(Items.DIRT, 64));
            var target = stone(6, 2, 6);
            var task = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("completed-support", 1000, List.of(target), false));
            var type = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var ctor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); ctor.setAccessible(true);
            Object original = ctor.newInstance(target, List.of());
            field("cell").set(task, original); field("queue").set(task, new ArrayList<>(List.of(original)));
            invoke(task, "prepareTemporarySupports");
            h.set(target.pos().below(), Blocks.DIRT.defaultBlockState());
            h.set(target.pos(), target.desiredState());
            invoke(task, "selectTick");
            check(field("supportedCell").get(task) == null && h.blockUses() == 0,
                    "external completion releases the support bundle without claiming another actor's blocks");
        }
    }
    private static void finish(BuildSupportAccess proof) {
        for (int i = 0; i < 4096; i++) if (proof.advance(16)) return;
        throw new AssertionError("support validation did not terminate within its finite budget");
    }
    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object invoke(FirstPersonBuildCompanionTask task, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static BuildTaskRecord.Target stone(int x, int y, int z) {
        return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(x, y, z), "target", null, null, null);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
