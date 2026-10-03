package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.server.physics.NativeWheelCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** 原生车轮照常计算和施力；这里只复制选中的接地样本、转向角和冲量，所有原参数保持原样。 */
@Pseudo
@Mixin(targets="dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity",remap=false)
public abstract class OffroadWheelObservationMixin {
    @Shadow protected abstract double getChasingYaw();
    @Shadow private double touchingFriction;

    @WrapMethod(method="sable$physicsTick",require=0,remap=false)
    private void maicraft$observeWheel(@Coerce Object ship,@Coerce Object handle,double dt,Operation<Void> original) {
        Object scope=NativeWheelCapture.begin(this,ship,getChasingYaw());boolean completed=false;
        try {original.call(ship,handle,dt);completed=true;}
        finally {NativeWheelCapture.finish(scope,completed);}
    }
    @ModifyArgs(method="computeMaxExtensionToTerrain",at=@At(value="INVOKE",
            target="Ldev/ryanhcode/offroad/content/blocks/wheel_mount/WheelMountBlockEntity$TerrainCastResult;<init>(DLnet/minecraft/core/Direction;Ldev/ryanhcode/sable/sublevel/SubLevel;Lnet/minecraft/core/BlockPos;)V"),require=0,remap=false)
    private void maicraft$observeTerrain(Args args) {
        // 构造器参数已经包含原生三条射线最终选择的高度和法线；读取 Args 但绝不调用 set 改写它们。
        NativeWheelCapture.terrain(this,(double)args.get(0),(Direction)args.get(1),args.get(2),(BlockPos)args.get(3));
    }
    @ModifyArgs(method="sable$physicsTick",at=@At(value="INVOKE",
            target="Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyImpulseAtPoint(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"),require=0,remap=false)
    private void maicraft$observeWheelImpulse(Args args) {
        NativeWheelCapture.force(this,(Vector3dc)args.get(1),(Vector3dc)args.get(2),touchingFriction);
    }
    @WrapOperation(method="applyBatchedForces",at=@At(value="INVOKE",
            target="Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;applyForcesAndReset(Ldev/ryanhcode/sable/api/physics/force/ForceTotal;)V"),require=0,remap=false)
    private void maicraft$observeAppliedWheel(@Coerce Object handle,@Coerce Object forces,Operation<Void> original) {
        original.call(handle,forces);NativeWheelCapture.applied(this);
    }
}
