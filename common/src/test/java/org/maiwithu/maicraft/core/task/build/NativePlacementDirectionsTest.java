// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Native Minecraft methods are the oracle; all player and world changes belong to this inert test fixture. */
public final class NativePlacementDirectionsTest {
    private NativePlacementDirectionsTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            compareNativeQueries(world);
            barrelTiesUseCandidateAnglesWithoutRotatingThePlayer(world);
            ladderPrioritizesTheClickedSupport(world);
            check(world.blockUses() == 0 && world.itemUses() == 0, "Prediction submitted a native game action");
        }
        System.out.println("NativePlacementDirectionsTest: native angle/tie ordering, support promotion, barrel and ladder placement parity passed");
    }

    private static void compareNativeQueries(InteractionWorldTestHarness world) {
        float[] yaws = {-540, -180, -135, -90, -45, -0F, 0F, 30, 45, 46.642803F, 60, 90, 135, 180, 540};
        float[] pitches = {-90, -60, Math.nextDown(-45F), -45, Math.nextUp(-45F), -0F, 0F,
                17.5165F, Math.nextDown(45F), 45, Math.nextUp(45F), 60, 90};
        BlockPos clicked = new BlockPos(8, 2, 8);
        for (float yaw : yaws) for (float pitch : pitches) {
            world.player.setYRot(yaw); world.player.setXRot(pitch);
            Direction[] nativeOrder = Direction.orderedByNearest(world.player);
            Direction[] candidate = NativePlacementDirections.ordered(yaw, pitch);
            check(Arrays.equals(candidate, nativeOrder), "Native direction order differs at " + yaw + "," + pitch);
            for (boolean replace : new boolean[]{false, true}) for (Direction face : Direction.values()) {
                world.set(clicked, replace ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
                BlockPlaceContext context = context(world, new ItemStack(Items.BARREL), hit(clicked, face));
                check(context.replacingClickedOnBlock() == replace, "Fixture did not select the requested replacement case");
                check(context.getNearestLookingDirection() == candidate[0], "Singular query inherited plural support promotion");
                check(context.getNearestLookingVerticalDirection() == NativePlacementDirections.vertical(pitch),
                        "Native vertical query differs at pitch " + pitch);
                Direction[] placement = NativePlacementDirections.forPlacement(candidate, face, replace);
                check(Arrays.equals(placement, context.getNearestLookingDirections()), "Native supporting-face order differs");
                placement[0] = null;
                check(Arrays.equals(candidate, nativeOrder), "Plural query modified the reusable singular ordering");
            }
            check(world.player.getYRot() == yaw && world.player.getXRot() == pitch, "Direction calculation rotated the player");
        }
        check(NativePlacementDirections.vertical(0F) == Direction.DOWN, "Level view must retain native DOWN vertical preference");
    }

    private static void barrelTiesUseCandidateAnglesWithoutRotatingThePlayer(InteractionWorldTestHarness world) {
        BlockPos support = new BlockPos(5, 1, 5);
        world.set(support, Blocks.STONE.defaultBlockState());
        BlockHitResult hit = hit(support, Direction.UP);
        ItemStack barrel = new ItemStack(Items.BARREL);
        for (float yaw : new float[]{-90, 0, 90}) for (float pitch : new float[]{45, -45}) {
            world.player.setYRot(yaw); world.player.setXRot(pitch);
            BlockState nativeState = Blocks.BARREL.getStateForPlacement(context(world, barrel, hit));
            if (yaw == -90 && pitch == 45)
                check(nativeState.getValue(BlockStateProperties.FACING) == Direction.WEST,
                        "Native barrel tie must face WEST, not the previously predicted UP");
            world.player.setYRot(17); world.player.setXRot(18);
            var target = new BuildTaskRecord.Target(nativeState, Items.BARREL, support.above(), "native barrel tie", null, null, null);
            check(nativeState.equals(BuildPlacementGeometry.predict(world.player, target, hit, yaw, pitch)),
                    "Candidate barrel prediction differs from native placement at " + yaw + "," + pitch);
            check(world.player.getYRot() == 17 && world.player.getXRot() == 18,
                    "Candidate barrel prediction changed the actual player rotation");
        }
        check(world.level.getBlockState(support).is(Blocks.STONE) && world.level.getBlockState(support.above()).isAir(),
                "Barrel prediction modified the support or destination");
    }

    private static void ladderPrioritizesTheClickedSupport(InteractionWorldTestHarness world) {
        BlockPos support = new BlockPos(10, 1, 5), destination = support.east();
        world.set(support, Blocks.STONE.defaultBlockState());
        world.set(destination.south(), Blocks.STONE.defaultBlockState());
        world.player.setYRot(0); world.player.setXRot(0);
        BlockHitResult hit = hit(support, Direction.EAST);
        BlockState nativeState = Blocks.LADDER.getStateForPlacement(context(world, new ItemStack(Items.LADDER), hit));
        check(nativeState != null && nativeState.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
                "Native ladder must prefer its clicked west support over another viable south support");
        world.player.setYRot(17); world.player.setXRot(18);
        var target = new BuildTaskRecord.Target(nativeState, Items.LADDER, destination, "native ladder support", null, null, null);
        check(nativeState.equals(BuildPlacementGeometry.predict(world.player, target, hit, 0, 0)),
                "Candidate ladder prediction ignored the native clicked-face promotion");
        check(world.player.getYRot() == 17 && world.player.getXRot() == 18 && world.level.getBlockState(destination).isAir(),
                "Ladder prediction changed the actual player or destination");
    }

    private static BlockPlaceContext context(InteractionWorldTestHarness world, ItemStack stack, BlockHitResult hit) {
        return new BlockPlaceContext(new UseOnContext(world.level, world.player, InteractionHand.MAIN_HAND, stack, hit) {});
    }
    private static BlockHitResult hit(BlockPos position, Direction face) {
        return new BlockHitResult(position.getCenter().add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
                face, position, false);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
