// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.maiwithu.maicraft.server.machine.mekanism.MekProductionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor", remap = false)
public abstract class MekMonitorProductionMixin {
    @Inject(method = "<init>(Lmekanism/common/recipe/lookup/IRecipeLookupHandler;I)V", at = @At("RETURN"), require = 0, remap = false)
    private void maicraft$registerOwner(@Coerce Object handler, int index, CallbackInfo callback) {
        MekProductionCapture.registerMonitor(this, handler, index);
    }

    @WrapOperation(method = "updateAndProcess()Z", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/recipes/cache/CachedRecipe;process()V"))
    private void maicraft$processWithOwner(@Coerce Object cached, Operation<Void> original) {
        MekProductionCapture.Process scope = MekProductionCapture.beginProcess(this, cached);
        boolean completed = false;
        try { original.call(cached); completed = true; }
        finally { MekProductionCapture.endProcess(scope, completed); }
    }
}
