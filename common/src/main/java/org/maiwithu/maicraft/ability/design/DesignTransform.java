// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/**
 * 对象的位置变换：组件先在自己的坐标里镜像、按四分之一周旋转，再放进父组件；几何与方块朝向共用同一个变换。
 * 只接受整九十度，组件边界才落得到方块格网上。Blender 的 z 朝上在这里换成 Minecraft 的 y 朝上。
 */
public final class DesignTransform {

    private static final int[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int[] BLENDER = {1, 0, 0, 0, 0, 1, 0, -1, 0};
    private static final int[][] QUARTERS = {{1, 0, 0, 0, 0, -1, 0, 1, 0}, {0, 0, 1, 0, 1, 0, -1, 0, 0}, {0, -1, 0, 1, 0, 0, 0, 0, 1}};

    /** 一格的整数坐标。 */
    public record Point(int x, int y, int z) {}

    /** 一段整数格范围，两端都包含。 */
    public record Box(Point from, Point to) {
        public boolean contains(Point p) {
            return p.x >= from.x && p.x <= to.x && p.y >= from.y && p.y <= to.y && p.z >= from.z && p.z <= to.z;
        }

        public long volume() {
            try {
                return Math.multiplyExact(Math.multiplyExact((long) to.x - from.x + 1, (long) to.y - from.y + 1), (long) to.z - from.z + 1);
            } catch (ArithmeticException overflow) {
                throw bad("对象尺寸超出工作量上限");
            }
        }
    }

    private final int[] axes;
    private final Vec3 translation;

    private DesignTransform(int[] axes, Vec3 translation) {
        this.axes = axes.clone();
        this.translation = translation;
    }

    static DesignTransform identity() {
        return new DesignTransform(IDENTITY, Vec3.ZERO);
    }

    /** 三乘三的带符号置换矩阵，按 Minecraft 的 x y z 逐行排。 */
    public int[] axes() {
        return axes.clone();
    }

    public Vec3 point(Vec3 local) {
        return direction(local).add(translation);
    }

    public Vec3 direction(Vec3 vector) {
        return apply(axes, vector);
    }

    public Vec3 inverse(Vec3 point) {
        return apply(transpose(axes), point.subtract(translation));
    }

    DesignTransform then(DesignTransform child) {
        return new DesignTransform(multiply(axes, child.axes), point(child.translation));
    }

    /** 从对象自己写的 mirror、rotation_euler、location 得到它在父坐标里的变换；阵列的那一份再加偏移。 */
    static DesignTransform local(JsonObject node, boolean blender, Vec3 arrayOffset) {
        int[] rotation = IDENTITY.clone();
        if (node.has("mirror")) {
            for (var value : node.getAsJsonArray("mirror")) {
                int axis = "xyz".indexOf(value.getAsString());
                rotation[axis * 3 + axis] *= -1;
            }
        }
        if (node.has("rotation_euler")) {
            JsonArray angles = node.getAsJsonArray("rotation_euler");
            // 欧拉角按局部轴 x、y、z 依次转；只接受整九十度，组件边界才落得到方块格网上。
            for (int axis = 0; axis < 3; axis++) {
                double turns = angles.get(axis).getAsDouble() / (Math.PI / 2);
                if (Math.abs(turns) > 1_000_000 || Math.abs(turns - Math.rint(turns)) > 1e-9) throw bad("rotation_euler 只能是直角的整数倍");
                for (int i = 0; i < Math.floorMod((int) Math.rint(turns), 4); i++) rotation = multiply(QUARTERS[axis], rotation);
            }
        }
        Vec3 position = vector(node, "location", Vec3.ZERO);
        if (blender) {
            rotation = multiply(multiply(BLENDER, rotation), transpose(BLENDER));
            position = apply(BLENDER, position);
            arrayOffset = apply(BLENDER, arrayOffset);
        }
        return new DesignTransform(rotation, position.add(apply(rotation, arrayOffset)));
    }

    static Vec3 dimensions(JsonObject node, boolean blender) {
        Vec3 value = vector(node, "dimensions", null);
        return blender ? new Vec3(value.x, value.z, value.y) : value;
    }

    /** 名义包围盒落到格网上的范围；非对齐的位置不能靠四舍五入悄悄多放一排墙。 */
    Box bounds(Vec3 dimensions, int radius) {
        double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int x : new int[]{-1, 1}) {
            for (int y : new int[]{-1, 1}) {
                for (int z : new int[]{-1, 1}) {
                    Vec3 corner = point(new Vec3(x * dimensions.x / 2, y * dimensions.y / 2, z * dimensions.z / 2));
                    double[] coordinates = {corner.x, corner.y, corner.z};
                    for (int axis = 0; axis < 3; axis++) {
                        low[axis] = Math.min(low[axis], coordinates[axis]);
                        high[axis] = Math.max(high[axis], coordinates[axis]);
                    }
                }
            }
        }
        int[] from = new int[3];
        int[] to = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(low[axis] + high[axis]) || Math.abs(low[axis] - Math.rint(low[axis])) > 1e-8
                    || Math.abs(high[axis] - Math.rint(high[axis])) > 1e-8) {
                throw bad("对象变换后的边界要落在方块面上");
            }
            if (low[axis] < -radius || high[axis] - 1 > radius) throw bad("展开后的对象超出坐标半径 " + radius);
            from[axis] = Math.toIntExact((long) Math.rint(low[axis]));
            to[axis] = Math.toIntExact((long) Math.rint(high[axis]) - 1);
        }
        return new Box(new Point(from[0], from[1], from[2]), new Point(to[0], to[1], to[2]));
    }

    static Vec3 vector(JsonObject object, String key, Vec3 fallback) {
        if (!object.has(key)) return fallback;
        JsonArray values = object.getAsJsonArray(key);
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    static JsonArray json(Vec3 vector) {
        JsonArray out = new JsonArray();
        out.add(vector.x);
        out.add(vector.y);
        out.add(vector.z);
        return out;
    }

    static Vec3 apply(int[] matrix, Vec3 value) {
        return new Vec3(matrix[0] * value.x + matrix[1] * value.y + matrix[2] * value.z,
                matrix[3] * value.x + matrix[4] * value.y + matrix[5] * value.z,
                matrix[6] * value.x + matrix[7] * value.y + matrix[8] * value.z);
    }

    private static int[] transpose(int[] matrix) {
        return new int[]{matrix[0], matrix[3], matrix[6], matrix[1], matrix[4], matrix[7], matrix[2], matrix[5], matrix[8]};
    }

    private static int[] multiply(int[] left, int[] right) {
        int[] result = new int[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int k = 0; k < 3; k++) result[row * 3 + column] += left[row * 3 + k] * right[k * 3 + column];
            }
        }
        return result;
    }
}
