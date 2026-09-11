// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.maiwithu.maicraft.server.machine.mekanism.MekProductionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "mekanism.api.recipes.cache.CachedRecipe", remap = false)
public abstract class MekCachedProductionMixin {
    @WrapOperation(method = "process", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/recipes/cache/CachedRecipe;finishProcessing(I)V"))
    private void maicraft$observeCompletion(@Coerce Object cached, int operations, Operation<Void> original) {
        MekProductionCapture.Completion scope = MekProductionCapture.beginCompletion(cached, operations);
        boolean completed = false;
        try { original.call(cached, operations); completed = true; }
        finally { MekProductionCapture.endCompletion(scope, completed); }
    }
}
