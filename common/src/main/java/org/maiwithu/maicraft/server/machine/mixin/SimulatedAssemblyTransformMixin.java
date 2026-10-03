package org.maiwithu.maicraft.server.machine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.server.physics.NativeAssemblyCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 原生方块搬移结束后读取实际结构和转换；不修改原返回值，也不再次执行搬移来取得证据。 */
@Pseudo
@Mixin(targets="dev.simulated_team.simulated.util.SimAssemblyHelper",remap=false)
public abstract class SimulatedAssemblyTransformMixin {
    @Inject(method="assembleFromSingleBlock",at=@At("RETURN"),require=0,remap=false)
    private static void maicraft$assembled(Level level,BlockPos self,BlockPos start,boolean includeStart,boolean includeGlue,
            CallbackInfoReturnable<Object> callback) { NativeAssemblyCapture.assembled(level,self,callback.getReturnValue()); }
    @Inject(method="disassembleSubLevel",at=@At("RETURN"),require=0,remap=false)
    private static void maicraft$disassembled(Level level,@Coerce Object ship,BlockPos from,BlockPos to,Rotation rotation,
            boolean sound,CallbackInfo callback) { NativeAssemblyCapture.disassembled(level,ship,from,to,rotation); }
}
