// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

public final class PhysicalStructurePerceptionTest {
    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        BlockPos plotOrigin = new BlockPos(30_000_008, 64, 30_000_008);
        Vec3 pivot = Vec3.atLowerCornerOf(plotOrigin);
        Quaterniond yaw = new Quaterniond().rotateY(Math.PI / 2);
        StructurePose pose = pose(new Vec3(10, 100, 20), yaw, pivot, new Vec3(2, 1, 1));
        var ship = structure(id, pose, pose, plotOrigin);
        BlockHitResult hit = new BlockHitResult(pivot.add(1.5, 1, .5), Direction.UP, plotOrigin.offset(1, 0, 0), false);
        var resolution = new SableStructureBridge.HitResolution("hit", id, null);
        var gaze = PhysicalStructurePerception.describeHit(resolution, ship, hit, new Vec3(10.5, 101, 10));
        check(gaze.get("state").getAsString().equals("physical_structure"), "native ship hit must be identified");
        check(gaze.get("structure_id").getAsString().equals(id.toString()), "gaze needs a stable structure identity");
        check(Math.abs(gaze.get("distance").getAsDouble() - 7) < 1e-7,
                "plotyard hit coordinates must be transformed before calculating world reach");
        check(gaze.getAsJsonObject("local_block").get("x").getAsInt() == 1
                && Math.abs(gaze.getAsJsonObject("world_hit").get("z").getAsDouble() - 17) < 1e-7,
                "zero-origin local coordinates and world coordinates must be distinct");
        var miss = PhysicalStructurePerception.describeHit(resolution, ship,
                BlockHitResult.miss(new Vec3(3, 4, 5), Direction.UP, BlockPos.ZERO), Vec3.ZERO);
        check(miss.get("state").getAsString().equals("miss"), "a bounding box alone must not become a gaze hit");
        var occluded = PhysicalStructurePerception.describeHit(new SableStructureBridge.HitResolution("not_structure", null, null),
                ship, new BlockHitResult(new Vec3(1, 2, 3), Direction.UP, new BlockPos(1, 1, 3), false), Vec3.ZERO);
        check(occluded.get("state").getAsString().equals("world_block") && !occluded.has("structure_id"),
                "a nearer world block must not select the ship behind it");
        var moved = structure(id, pose(new Vec3(12, 100, 20), yaw, pivot, new Vec3(2, 1, 1)), pose, plotOrigin);
        var metadata = PhysicalStructurePerception.describe(moved);
        check(metadata.get("structure_id").getAsString().equals(id.toString())
                && Math.abs(metadata.getAsJsonObject("motion").getAsJsonObject("linear_blocks_per_second").get("x").getAsDouble() - 40) < 1e-8,
                "motion must use the new pose, not retain a previous world-space deck target");
        var unavailable = structure(id, null, null, plotOrigin);
        var unknown = PhysicalStructurePerception.describeHit(resolution, unavailable, hit, Vec3.ZERO);
        check(unknown.get("state").getAsString().equals("unknown") && !unknown.has("world_hit"),
                "missing pose must not expose a guessed world hit");
        StructurePose a = pose(Vec3.ZERO, new Quaterniond().rotateY(Math.toRadians(359)), Vec3.ZERO, new Vec3(1,1,1));
        StructurePose b = pose(Vec3.ZERO, new Quaterniond().rotateY(Math.toRadians(1)), Vec3.ZERO, new Vec3(1,1,1));
        check(Math.abs(PhysicalStructurePerception.angularVelocity(a,b).y - Math.toRadians(2) * 20) < 1e-8,
                "angular estimate must use the shortest rotation across the wrap boundary");
        StructurePose negativeIdentity = new StructurePose(Vec3.ZERO, 0, 0, 0, -1, Vec3.ZERO, new Vec3(1,1,1));
        check(PhysicalStructurePerception.angularVelocity(pose(Vec3.ZERO, new Quaterniond(), Vec3.ZERO, new Vec3(1,1,1)),
                negativeIdentity).lengthSqr() == 0, "equivalent quaternion signs must not imply a full spin");
        AABB edgeBody = new AABB(15.1, 100, 5, 15.7, 101.8, 5.6);
        check(PhysicalStructurePerception.mainWorldClearance(edgeBody, (min, max) -> max.getX() < 16,
                () -> { throw new AssertionError("missing neighbor must not fall through to noCollision"); })
                .equals("unknown_unloaded"), "protruding shapes from the adjacent chunk must remain unknown");
        check(PhysicalStructurePerception.mainWorldClearance(edgeBody, (min, max) -> true, () -> false)
                .equals("obstructed_now"), "loaded clearance must preserve actual native obstruction");
        System.out.println("PhysicalStructurePerceptionTest: passed");
    }

    private static StructurePose pose(Vec3 position, Quaterniond q, Vec3 pivot, Vec3 scale) {
        return new StructurePose(position, q.x, q.y, q.z, q.w, pivot, scale);
    }
    private static SableStructureBridge.Structure structure(UUID id, StructurePose pose, StructurePose previous, BlockPos center) {
        return new SableStructureBridge.Structure(id, "fixture ship", true, pose, previous,
                new AABB(8, 99, 10, 15, 105, 25), center,
                new AABB(center.getX(), 64, center.getZ(), center.getX()+3, 68, center.getZ()+3),
                List.of(), pose == null ? Map.of("pose", "unavailable") : Map.of());
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
