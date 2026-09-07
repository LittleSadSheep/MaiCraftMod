// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Immutable copied pose. Storage coordinates include the native plotyard offset. */
public record StructurePose(Vec3 position, double orientationX, double orientationY,
        double orientationZ, double orientationW, Vec3 pivot, Vec3 scale) {
    public StructurePose {
        requireFinite(position);
        requireFinite(pivot);
        requireFinite(scale);
        if (scale.x <= 0 || scale.y <= 0 || scale.z <= 0) {
            throw new IllegalArgumentException("pose scale must be positive");
        }
        double length = Math.hypot(Math.hypot(orientationX, orientationY),
                Math.hypot(orientationZ, orientationW));
        if (!Double.isFinite(length) || length == 0) {
            throw new IllegalArgumentException("pose orientation must be finite and nonzero");
        }
        orientationX /= length;
        orientationY /= length;
        orientationZ /= length;
        orientationW /= length;
    }

    public static StructurePose copyOf(Vector3dc position, Quaterniondc orientation,
            Vector3dc pivot, Vector3dc scale) {
        return new StructurePose(copy(position), orientation.x(), orientation.y(),
                orientation.z(), orientation.w(), copy(pivot), copy(scale));
    }

    public Vec3 toWorld(Vec3 storage) {
        requireFinite(storage);
        Vector3d relative = new Vector3d(storage.x - pivot.x, storage.y - pivot.y,
                storage.z - pivot.z).mul(scale.x, scale.y, scale.z);
        return copy(rotation().transform(relative).add(position.x, position.y, position.z));
    }

    public Vec3 toStorage(Vec3 world) {
        requireFinite(world);
        Vector3d relative = rotation().transformInverse(new Vector3d(
                world.x - position.x, world.y - position.y, world.z - position.z));
        relative.set(relative.x / scale.x + pivot.x, relative.y / scale.y + pivot.y,
                relative.z / scale.z + pivot.z);
        return copy(relative);
    }

    /** Unit surface normal transformed by inverse transpose, including nonuniform scale. */
    public Vec3 normalToWorld(Vec3 normal) {
        requireFinite(normal);
        Vector3d transformed = rotation().transform(new Vector3d(
                normal.x / scale.x, normal.y / scale.y, normal.z / scale.z));
        double length = Math.hypot(Math.hypot(transformed.x, transformed.y), transformed.z);
        if (!Double.isFinite(length) || length == 0) {
            throw new IllegalArgumentException("transformed normal must be finite and nonzero");
        }
        return copy(transformed.div(length));
    }

    private Quaterniond rotation() {
        return new Quaterniond(orientationX, orientationY, orientationZ, orientationW);
    }

    private static Vec3 copy(Vector3dc source) {
        Vec3 result = new Vec3(source.x(), source.y(), source.z());
        requireFinite(result);
        return result;
    }

    private static void requireFinite(Vec3 value) {
        if (value == null || !Double.isFinite(value.x) || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException("pose vector must be finite");
        }
    }
}
