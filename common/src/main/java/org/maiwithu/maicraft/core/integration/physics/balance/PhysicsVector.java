package org.maiwithu.maicraft.core.integration.physics.balance;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/** 将原生物理线程的向量复制成不可变数据，分析船体时不会改到正在运行的物理对象。 */
public record PhysicsVector(double x, double y, double z) {
    public static final PhysicsVector ZERO = new PhysicsVector(0, 0, 0);
    public PhysicsVector {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("物理向量必须是有限数值");
    }
    public static PhysicsVector of(Vector3dc v) { return new PhysicsVector(v.x(), v.y(), v.z()); }
    public Vector3d mutable() { return new Vector3d(x, y, z); }
    public PhysicsVector add(PhysicsVector v) { return new PhysicsVector(x + v.x, y + v.y, z + v.z); }
    public PhysicsVector subtract(PhysicsVector v) { return add(v.scale(-1)); }
    public PhysicsVector scale(double k) { return new PhysicsVector(x * k, y * k, z * k); }
    public double dot(PhysicsVector v) { return x * v.x + y * v.y + z * v.z; }
    public PhysicsVector cross(PhysicsVector v) {
        return new PhysicsVector(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }
    public double length() { return Math.sqrt(dot(this)); }
}
