package org.maiwithu.maicraft.core.integration.physics.balance;

/** 同一子步分别保留受力输入和直接冲量提交后的速度，避免拿后者倒算轮胎、机翼当时的阻尼。 */
public record PhysicsMotion(PhysicsVector forceInputVelocity,PhysicsVector forceInputAngularVelocity,
                            PhysicsVector afterDirectImpulseVelocity,PhysicsVector afterDirectImpulseAngularVelocity,
                            double substepSeconds) {
    public PhysicsMotion {
        if((forceInputVelocity==null)!=(forceInputAngularVelocity==null)||afterDirectImpulseVelocity==null
                ||afterDirectImpulseAngularVelocity==null||!Double.isFinite(substepSeconds)||substepSeconds<=0)
            throw new IllegalArgumentException("原生运动阶段样本不完整");
    }
}
