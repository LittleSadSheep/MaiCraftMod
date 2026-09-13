// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Native pair limits plus a conservative clear envelope around both exposed chain runs. */
public final class ChainConveyorGeometry {
    public record Strand(Vec3 start, Vec3 end) {}
    private ChainConveyorGeometry() {}
    public static void validate(BlockPos first, BlockPos second, ChainConveyorBridge.Limits limits) {
        Vec3 delta = Vec3.atLowerCornerOf(second.subtract(first)); double horizontal = Math.hypot(delta.x, delta.z);
        if (delta.length() < 2.5) throw new IllegalArgumentException("chain_conveyor_too_close");
        if (delta.length() >= limits.maximumLength()) throw new IllegalArgumentException("chain_conveyor_too_long");
        if (horizontal <= 1.5 || Math.abs(delta.y) > horizontal - 1.5) throw new IllegalArgumentException("chain_conveyor_too_steep");
    }
    public static void clearEnvelope(Level world, BlockPos first, BlockPos second) {
        for (BlockPos pulley : List.of(first, second)) checkBox(world, first, second, around(Vec3.atBottomCenterOf(pulley).add(0, .375, 0), 1.4));
        for (Strand strand : strands(first, second)) {
            int samples = (int) Math.ceil(strand.start.distanceTo(strand.end) * 4);
            if (samples > 1024) throw new IllegalArgumentException("chain_conveyor_corridor_budget_exceeded");
            for (int index = 0; index <= samples; index++)
                checkBox(world, first, second, around(strand.start.lerp(strand.end, samples == 0 ? 0 : (double) index / samples), .2));
        }
    }
    /** Mirrors the two native ConnectionStats tangents: 1.25-block radius, +/-35 degrees, height .375. */
    public static List<Strand> strands(BlockPos first, BlockPos second) {
        BlockPos delta = second.subtract(first); double theta = Math.atan2(delta.getX(), delta.getZ());
        Vec3 a = Vec3.atBottomCenterOf(first).add(0, .375, 0), b = Vec3.atBottomCenterOf(second).add(0, .375, 0);
        return List.of(strand(a, b, theta, 1), strand(a, b, theta, -1));
    }
    private static Strand strand(Vec3 first, Vec3 second, double theta, int sign) {
        double offset = Math.toRadians(35) * sign;
        return new Strand(first.add(new Vec3(0, 0, 1.25).yRot((float) (theta - offset))),
                second.add(new Vec3(0, 0, 1.25).yRot((float) (theta + Math.PI + offset))));
    }
    private static AABB around(Vec3 point, double width) {
        return new AABB(point.x - width, point.y - .2, point.z - width, point.x + width, point.y + .2, point.z + width);
    }
    private static void checkBox(Level world, BlockPos first, BlockPos second, AABB envelope) {
            for (BlockPos probe : BlockPos.betweenClosed(BlockPos.containing(envelope.minX, envelope.minY, envelope.minZ),
                    BlockPos.containing(envelope.maxX, envelope.maxY, envelope.maxZ))) {
                if (!world.isLoaded(probe)) throw new IllegalArgumentException("chain_conveyor_corridor_unloaded");
                if (probe.equals(first) || probe.equals(second)) {
                    if (NavigationSafetyContext.protectsUse(probe)) throw new IllegalArgumentException("chain_conveyor_endpoint_protected");
                    continue;
                }
                if (!world.getWorldBorder().isWithinBounds(probe) || world.isOutsideBuildHeight(probe)
                        || NavigationSafetyContext.protectsMutation(probe) || NavigationSafetyContext.forbidsBody(probe))
                    throw new IllegalArgumentException("chain_conveyor_corridor_protected");
                if (!world.getFluidState(probe).isEmpty()) throw new IllegalArgumentException("chain_conveyor_corridor_fluid");
                for (AABB shape : world.getBlockState(probe).getCollisionShape(world, probe).toAabbs())
                    if (shape.move(probe).intersects(envelope)) throw new IllegalArgumentException("chain_conveyor_corridor_obstructed");
            }
    }
}
