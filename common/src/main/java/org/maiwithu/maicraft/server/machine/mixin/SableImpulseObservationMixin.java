package org.maiwithu.maicraft.server.machine.mixin;

import org.joml.Vector3dc;
import org.maiwithu.maicraft.server.physics.NativePhysicsCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 反作用轮和部分附属模组直接给刚体冲量，旁听成功的原生提交，避免只看图解箭头漏掉力偶。 */
@Pseudo
@Mixin(targets="dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline",remap=false)
public abstract class SableImpulseObservationMixin {
    @Inject(method="applyLinearAndAngularImpulse",at=@At("RETURN"),require=0,remap=false)
    private void maicraft$observeImpulse(@Coerce Object body,Vector3dc force,Vector3dc torque,boolean wake,CallbackInfo ci) {
        NativePhysicsCapture.direct(body,force,torque);
    }
    @Inject(method="applyImpulse",at=@At("RETURN"),require=0,remap=false)
    private void maicraft$observePoint(@Coerce Object body,Vector3dc point,Vector3dc force,CallbackInfo ci) {
        NativePhysicsCapture.directPoint(body,point,force);
    }
}
