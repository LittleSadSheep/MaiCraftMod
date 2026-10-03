package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.maiwithu.maicraft.server.physics.NativeAssemblyCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** 让原生玩家请求照常运行，只用其真实玩家身份关联观察；抛出异常时也清除本次旁听作用域。 */
@Pseudo
@Mixin(targets="dev.simulated_team.simulated.network.packets.AssemblePacket",remap=false)
public abstract class SimulatedAssemblyRequestMixin {
    @WrapMethod(method="handle",require=0,remap=false)
    private void maicraft$observeAssemblyRequest(@Coerce Object context,Operation<Void> original) {
        Object scope=NativeAssemblyCapture.begin(this,context);boolean completed=false;
        try { original.call(context);completed=true; }
        finally { NativeAssemblyCapture.finish(scope,completed); }
    }
}
