package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;

/** A short approach may continue while aiming if it stays clear both before and after the click. */
final class BuildPlacementMotion {
    private BuildPlacementMotion() {}

    static boolean continueApproach(LocalPlayerContext context, Vec3 destination,
                                    Map<BlockPos, BlockState> effects, LongSet forbidden) {
        LocalPlayer player = context.player();
        if (destination == null || !player.onGround() || player.isInWater() || player.isPassenger()
                || player.getPose() != net.minecraft.world.entity.Pose.STANDING
                || player.isShiftKeyDown() || player.position().distanceToSqr(destination) < .09
                || player.position().distanceToSqr(destination) > 25) return false;
        var world = player.level();
        var obstacles = EmbeddedBaritoneRuntime.physicalObstacles();
        java.util.function.Predicate<BlockPos> loaded = pos -> world.isLoaded(pos) && world.getWorldBorder().isWithinBounds(pos);
        var live = new GroundCorridor(world, loaded, player.getBbWidth(), player.getBbHeight(), forbidden, obstacles);
        var after = new GroundCorridor(afterPlacement(world, effects), loaded,
                player.getBbWidth(), player.getBbHeight(), forbidden, obstacles);
        Vec3 position = player.position();
        Vec3 drift = position.add(player.getDeltaMovement().multiply(3, 0, 3));
        if (!live.clear(position, destination) || !after.clear(position, destination)
                || !live.clear(position, drift) || !after.clear(position, drift)) return false;
        context.body().applySteering(yaw -> toward(position, destination, yaw), player.getYRot(), context.tickRevision());
        return true;
    }

    static BodyControlPort.Movement toward(Vec3 position, Vec3 destination, float yaw) {
        Vec3 direction = destination.subtract(position).multiply(1, 0, 1).normalize();
        double angle = Math.toRadians(yaw);
        float forward = (float) (-direction.x * Math.sin(angle) + direction.z * Math.cos(angle));
        float strafe = (float) (direction.x * Math.cos(angle) + direction.z * Math.sin(angle));
        return new BodyControlPort.Movement(forward, strafe, false, false, false);
    }

    static BlockGetter afterPlacement(BlockGetter world, Map<BlockPos, BlockState> effects) {
        return new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) { return effects.containsKey(pos) ? effects.get(pos) : world.getBlockState(pos); }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { return world.getBlockEntity(pos); }
            public int getHeight() { return world.getHeight(); }
            public int getMinBuildHeight() { return world.getMinBuildHeight(); }
        };
    }
}
