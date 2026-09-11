// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Actual Create placement bytecode with an explicit fixture getter bridge, not a claim that production Mixins ran. */
public final class BuildPlacementSneakCreateTest {
    private record State(Input input, boolean shift, boolean jumping, float forward, float sideways, boolean crouching,
                         Vec3 position, AABB bounds, float yaw, float pitch, Object entityData) {}
    private BuildPlacementSneakCreateTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: BuildPlacementSneakCreateTest <actual Create.jar>");
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness(); var create = new BuildPlacementSneakCreateHarness(Path.of(args[0]))) {
            world.position(new Vec3(6.5, 1, 8.5));
            BlockPos support = new BlockPos(8, 1, 8); world.set(support, Blocks.STONE.defaultBlockState());
            exercise(world, create, support, false, true, Direction.EAST);
            exercise(world, create, support, true, false, Direction.WEST);
            check(world.blockUses() == 0 && world.itemUses() == 0, "placement prediction must never submit a native item/block operation");
            check(world.level.getBlockState(support).is(Blocks.STONE) && world.level.getBlockState(support.above()).isAir(),
                    "native state calculation must leave the support and destination unchanged");
        }
        System.out.println("BuildPlacementSneakCreateTest: actual CreativeMotorBlock EAST/WEST and exception restoration passed; fixture getter bridge only");
    }

    private static void exercise(InteractionWorldTestHarness world, BuildPlacementSneakCreateHarness create, BlockPos support,
                                 boolean actualSneak, boolean candidateSneak, Direction expected) throws Exception {
        LocalPlayer player = BuildPlacementSneakCreateHarness.player(world.player, actualSneak);
        State before = state(player);
        ItemStack stack = new ItemStack(create.item);
        BlockHitResult hit = new BlockHitResult(support.getCenter().add(0, .5, 0), Direction.UP, support, false);
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(world.level, player, InteractionHand.MAIN_HAND, stack, hit) {}) {
            @Override public boolean isSecondaryUseActive() { return candidateSneak; }
            @Override public Direction getNearestLookingDirection() { return Direction.EAST; }
            @Override public Direction getHorizontalDirection() { return Direction.EAST; }
        };
        check(context.getClickedPos().equals(support.above()), "native placement must target the adjacent air cell");
        Direction unprojected = create.motor.getStateForPlacement(context).getValue(BlockStateProperties.FACING);
        check(unprojected == (actualSneak ? Direction.EAST : Direction.WEST) && unprojected != expected,
                "actual Create directly reads player sneak even when context secondary-use advertises the opposite candidate");
        BlockState predicted = predict(player, stack, hit, candidateSneak, support.above());
        check(predicted != null && predicted.getValue(BlockStateProperties.FACING) == expected,
                "BuildPlacementGeometry must wrap its actual CreativeMotorBlock call with candidate sneak");
        check(state(player).equals(before) && player.isShiftKeyDown() == actualSneak && stack.getCount() == 1,
                "prediction cannot modify real input, pose, location, rotation, shared data or carried stack");
        BlockPos edgeSupport = new BlockPos(15, 1, 8); world.set(edgeSupport, Blocks.STONE.defaultBlockState());
        BlockHitResult edgeHit = new BlockHitResult(edgeSupport.getCenter().add(0, .5, 0), Direction.UP, edgeSupport, false);
        try {
            predict(player, stack, edgeHit, candidateSneak, edgeSupport.above());
            throw new AssertionError("actual Create neighbor lookup did not reach the fixture's unloaded boundary");
        } catch (InvocationTargetException expectedFailure) {
            check(expectedFailure.getCause() instanceof AssertionError
                            && expectedFailure.getCause().getMessage().contains("read from an unloaded cell"),
                    "expected the real native placement method to propagate the inert world's boundary exception");
        }
        check(state(player).equals(before) && player.isShiftKeyDown() == actualSneak,
                "exception exit must restore the getter scope without touching player state");
    }

    private static BlockState predict(LocalPlayer player, ItemStack stack, BlockHitResult hit, boolean sneak, BlockPos target) throws Exception {
        var prediction = BuildPlacementGeometry.class.getDeclaredMethod("predictedState", LocalPlayer.class, ItemStack.class,
                BlockHitResult.class, float.class, float.class, boolean.class, BlockPos.class);
        prediction.setAccessible(true);
        return (BlockState) prediction.invoke(null, player, stack, hit, -90F, 0F, sneak, target);
    }

    private static State state(LocalPlayer player) throws Exception {
        Input input = player.input;
        return new State(input, input.shiftKeyDown, input.jumping, input.forwardImpulse, input.leftImpulse,
                BuildPlacementSneakCreateHarness.field(LocalPlayer.class, "crouching").getBoolean(player),
                player.position(), player.getBoundingBox(), player.getYRot(), player.getXRot(),
                BuildPlacementSneakCreateHarness.field(Entity.class, "entityData").get(player));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
