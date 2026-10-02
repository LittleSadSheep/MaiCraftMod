package org.maiwithu.maicraft.server.machine.mixin;

import org.maiwithu.maicraft.server.physics.NativePhysicsCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在清空旧冲量后开启按需记录，在气球等原生力全部入队且尚未清空时复制受力。 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ServerSubLevel", remap = false)
public abstract class SableForceObservationMixin {
    @Inject(method = "prePhysicsTickBegin", at = @At("RETURN"), require = 0, remap = false)
    private void maicraft$beginForceObservation(CallbackInfo callback) { NativePhysicsCapture.begin(this); }

    @Inject(method = "applyQueuedForces", at = @At("HEAD"), require = 0, remap = false)
    private void maicraft$captureForces(@Coerce Object system, @Coerce Object handle, double timeStep, CallbackInfo callback) {
        NativePhysicsCapture.capture(this, handle, timeStep);
    }
}
