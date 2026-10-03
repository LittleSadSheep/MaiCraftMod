package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import java.util.UUID;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/** 船体几何位置均相对结构原点；受力分析、候选改造和时间推进只操作这份快照。 */
public record PhysicsBody(UUID structureId, String dimension, long tick, double mass,
                          PhysicsVector center, Inertia inertia, Rotation rotation,
                          PhysicsVector position, PhysicsVector velocity, PhysicsVector angularVelocity,
                          PhysicsVector gravity, List<Load> loads, List<String> unknowns) {
    public PhysicsBody {
        if (structureId == null || dimension == null || tick < 0 || !Double.isFinite(mass) || mass <= 0
                || center == null || inertia == null || rotation == null || position == null
                || velocity == null || angularVelocity == null || gravity == null)
            throw new IllegalArgumentException("船体快照缺少有效的质量、坐标或运动状态");
        loads = List.copyOf(loads); unknowns = List.copyOf(unknowns);
        if (loads.stream().map(Load::id).distinct().count() != loads.size())
            throw new IllegalArgumentException("受力来源编号重复");
    }

    /** 转动惯量保留非对角项；斜向配重不能用三个互不关联的转动惯量代替。 */
    public record Inertia(double xx, double yy, double zz, double xy, double xz, double yz) {
        public Inertia {
            if (!Double.isFinite(xx + yy + zz + xy + xz + yz) || xx <= 0
                    || xx * yy - xy * xy <= 1e-12 || matrix(xx, yy, zz, xy, xz, yz).determinant() <= 1e-12)
                throw new IllegalArgumentException("船体转动惯量不是正定矩阵");
        }
        private static Matrix3d matrix(double xx, double yy, double zz, double xy, double xz, double yz) {
            return new Matrix3d(xx, xy, xz, xy, yy, yz, xz, yz, zz);
        }
        public Matrix3d matrix() { return matrix(xx, yy, zz, xy, xz, yz); }
        public static Inertia of(Matrix3dc m) {
            return new Inertia(m.m00(), m.m11(), m.m22(), (m.m01() + m.m10()) / 2,
                    (m.m02() + m.m20()) / 2, (m.m12() + m.m21()) / 2);
        }
    }

    /** 姿态按四元数保存，启停过程中跨过正负一百八十度时不会突然翻转。 */
    public record Rotation(double x, double y, double z, double w) {
        public Rotation {
            double n = Math.sqrt(x * x + y * y + z * z + w * w);
            if (!Double.isFinite(n) || n < 1e-12) throw new IllegalArgumentException("船体姿态无效");
            x /= n; y /= n; z /= n; w /= n;
        }
        public static Rotation of(Quaterniondc q) { return new Rotation(q.x(), q.y(), q.z(), q.w()); }
        public Quaterniond mutable() { return new Quaterniond(x, y, z, w); }
        public PhysicsVector world(PhysicsVector v) { return PhysicsVector.of(mutable().transform(v.mutable())); }
        public PhysicsVector local(PhysicsVector v) { return PhysicsVector.of(mutable().transformInverse(v.mutable())); }
    }

    public enum Frame { BODY, WORLD }

    /** 起飞前只改变预测副本的参考航速，让模型比较不同滑跑或巡航速度，真实结构及实测样本保持原状。 */
    public PhysicsBody movingAt(PhysicsVector referenceVelocity) {
        return new PhysicsBody(structureId,dimension,tick,mass,center,inertia,rotation,position,referenceVelocity,
                angularVelocity,gravity,loads,unknowns);
    }

    /** 作用点随船体移动；气球浮力保持世界方向，螺旋桨推力随船体转动，纯力偶单独保留。 */
    public record Load(String id, String group, PhysicsVector point, PhysicsVector force,
                       PhysicsVector torque, Frame frame, boolean propulsion, double responseSeconds, double airflow,
                       PhysicsAerodynamics aerodynamics) {
        public Load(String id,String group,PhysicsVector point,PhysicsVector force,PhysicsVector torque,
                    Frame frame,boolean propulsion,double responseSeconds) {
            this(id,group,point,force,torque,frame,propulsion,responseSeconds,0,null);
        }
        public Load(String id,String group,PhysicsVector point,PhysicsVector force,PhysicsVector torque,
                    Frame frame,boolean propulsion,double responseSeconds,double airflow) {
            this(id,group,point,force,torque,frame,propulsion,responseSeconds,airflow,null);
        }
        public Load {
            if (id == null || id.isBlank() || group == null || point == null || force == null || torque == null
                    || frame == null || !Double.isFinite(responseSeconds+airflow) || responseSeconds < 0 || airflow < 0)
                throw new IllegalArgumentException("受力来源缺少作用点、方向或响应时间");
            // 帆面随结构朝向转动，停桨后仍产生气动载荷，不能把它误标为随开关消失的推进器。
            if(aerodynamics!=null&&(frame!=Frame.BODY||propulsion))
                throw new IllegalArgumentException("升力面必须使用船体坐标且不能归为推进开关");
        }
    }

    /** 在副本里加减真实方块质量，同时重新计算质心及惯量；原世界不发生方块或背包变化。 */
    public PhysicsBody ballast(double deltaMass, PhysicsVector at, Matrix3dc blockInertia) {
        if (!Double.isFinite(deltaMass) || mass + deltaMass <= 0) throw new IllegalArgumentException("配重后质量无效");
        double nextMass = mass + deltaMass;
        PhysicsVector nextCenter = center.scale(mass).add(at.scale(deltaMass)).scale(1 / nextMass);
        Matrix3d next = inertia.matrix().add(parallel(center.subtract(nextCenter), mass))
                .add(blockInertia).add(parallel(at.subtract(nextCenter), deltaMass));
        return new PhysicsBody(structureId, dimension, tick, nextMass, nextCenter, Inertia.of(next), rotation,
                position.add(rotation.world(nextCenter.subtract(center))), velocity, angularVelocity, gravity, loads, unknowns);
    }

    private static Matrix3d parallel(PhysicsVector r, double mass) {
        // 方块远离质心时不仅改变总重量，也会增加转弯惯性；拆除时保留质量变化的符号。
        return new Matrix3d(r.y()*r.y()+r.z()*r.z(), -r.x()*r.y(), -r.x()*r.z(),
                -r.x()*r.y(), r.x()*r.x()+r.z()*r.z(), -r.y()*r.z(),
                -r.x()*r.z(), -r.y()*r.z(), r.x()*r.x()+r.y()*r.y()).scale(mass);
    }
}
