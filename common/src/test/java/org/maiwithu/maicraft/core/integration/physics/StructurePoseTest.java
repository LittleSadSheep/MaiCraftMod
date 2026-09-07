// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public final class StructurePoseTest {
    public static void main(String[] args) {
        Vector3d position = new Vector3d(10, 30, -4);
        Vector3d pivot = new Vector3d(20_001_032, 128, -20_001_032);
        Vector3d scale = new Vector3d(2, 3, 4);
        Quaterniond rotation = new Quaterniond().rotateY(Math.PI / 2);
        StructurePose pose = StructurePose.copyOf(position, rotation, pivot, scale);
        Vec3 storage = new Vec3(pivot.x + 1, pivot.y + 2, pivot.z + 3);
        near(pose.toWorld(storage), new Vec3(22, 36, -6), 1E-8,
                "large storage pivot was omitted or scale/rotation order changed");
        near(pose.toStorage(pose.toWorld(storage)), storage, 1E-7, "world/storage transform did not round trip");
        position.zero(); pivot.zero(); scale.zero(); rotation.identity();
        near(pose.toWorld(storage), new Vec3(22, 36, -6), 1E-8, "pose retained mutable native vectors");

        Vec3 normal = pose.normalToWorld(new Vec3(1, 1, 0));
        near(normal, new Vec3(0, 2 / Math.sqrt(13), -3 / Math.sqrt(13)), 1E-10,
                "surface normal did not use inverse transpose for nonuniform scale");
        Vec3 tangent = pose.toWorld(pose.pivot().add(1, -1, 0)).subtract(pose.toWorld(pose.pivot()));
        check(Math.abs(normal.dot(tangent)) < 1E-10 && Math.abs(normal.length() - 1) < 1E-10,
                "transformed normal is not perpendicular and normalized");
        StructurePose normalized = new StructurePose(Vec3.ZERO, 0, 0, 0, 2, Vec3.ZERO, new Vec3(1, 1, 1));
        near(normalized.toWorld(new Vec3(1, 2, 3)), new Vec3(1, 2, 3), 0, "quaternion was not normalized");

        rejects(() -> new StructurePose(Vec3.ZERO, 0, 0, 0, 0, Vec3.ZERO, new Vec3(1, 1, 1)));
        rejects(() -> new StructurePose(Vec3.ZERO, 0, 0, 0, Double.NaN, Vec3.ZERO, new Vec3(1, 1, 1)));
        rejects(() -> new StructurePose(Vec3.ZERO, 0, 0, 0, 1, Vec3.ZERO, new Vec3(0, 1, 1)));
        rejects(() -> new StructurePose(Vec3.ZERO, 0, 0, 0, 1, Vec3.ZERO, new Vec3(-1, 1, 1)));
        rejects(() -> new StructurePose(new Vec3(Double.POSITIVE_INFINITY, 0, 0),
                0, 0, 0, 1, Vec3.ZERO, new Vec3(1, 1, 1)));
        rejects(() -> pose.toWorld(new Vec3(Double.MAX_VALUE, 0, 0)));
        rejects(() -> pose.toStorage(new Vec3(Double.NaN, 0, 0)));
        rejects(() -> pose.normalToWorld(Vec3.ZERO));
        System.out.println("StructurePoseTest: passed");
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid pose input was accepted");
    }
    private static void near(Vec3 actual, Vec3 expected, double tolerance, String message) {
        check(actual.distanceTo(expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
