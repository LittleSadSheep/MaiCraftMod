// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 复制结构的位置、转动、缩放和旋转中心，在结构的存储坐标与实际世界坐标之间转换；不持有会被模组继续修改的向量。
 */
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

    // 先减去结构旋转中心，再缩放、旋转并移到世界位置，不能直接把很远的存储区坐标当作世界地点。
    public Vec3 toWorld(Vec3 storage) {
        requireFinite(storage);
        Vector3d relative = new Vector3d(storage.x - pivot.x, storage.y - pivot.y,
                storage.z - pivot.z).mul(scale.x, scale.y, scale.z);
        return copy(rotation().transform(relative).add(position.x, position.y, position.z));
    }

    // 按相反顺序撤销平移、旋转和缩放，恢复结构内部的存储位置。
    public Vec3 toStorage(Vec3 world) {
        requireFinite(world);
        Vector3d relative = rotation().transformInverse(new Vector3d(
                world.x - position.x, world.y - position.y, world.z - position.z));
        relative.set(relative.x / scale.x + pivot.x, relative.y / scale.y + pivot.y,
                relative.z / scale.z + pivot.z);
        return copy(relative);
    }

    /**
     * 表面朝向要按缩放的倒数修正后再旋转、归一化；非等比缩放时不能直接照搬位置的转换方式。
     */
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
