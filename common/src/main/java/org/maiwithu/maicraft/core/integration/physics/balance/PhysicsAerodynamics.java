package org.maiwithu.maicraft.core.integration.physics.balance;

/** 按 Sable 的帆面算法重算速度相关载荷；参数来自实际方块，不把静止时的零升力冻结到起飞预测中。 */
public record PhysicsAerodynamics(PhysicsVector normal, double parallelDrag, double directionlessDrag,
                                  double lift, double pressure, double nativeStepSeconds) {
    public PhysicsAerodynamics {
        if(normal==null||Math.abs(normal.length()-1)>1e-6
                ||!Double.isFinite(parallelDrag+directionlessDrag+lift+pressure+nativeStepSeconds)
                ||parallelDrag<0||directionlessDrag<0||lift<0||pressure<0||nativeStepSeconds<=0)
            throw new IllegalArgumentException("升力面缺少有效法线、原生系数、气压或物理子步时长");
    }
    public PhysicsVector force(PhysicsVector localVelocity) {
        // 原生先计算法向阻力冲量，再从点速度中扣除该冲量来计算升力；必须保留原生子步，不能另换真实航空公式。
        PhysicsVector parallel=normal.scale(normal.dot(localVelocity)*parallelDrag);
        PhysicsVector remaining=localVelocity.subtract(parallel.scale(pressure*nativeStepSeconds));
        return parallel.add(localVelocity.scale(directionlessDrag))
                .add(normal.scale(remaining.length()*lift)).scale(-pressure);
    }
}
