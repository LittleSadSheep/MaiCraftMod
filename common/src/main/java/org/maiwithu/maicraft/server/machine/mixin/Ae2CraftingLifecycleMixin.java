// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import org.maiwithu.maicraft.server.inventory.Ae2NativeCraftingCompletion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Standalone AE2 19.2 links have no Nexus, so markDone does not make isDone true. Observe the decision itself. */
@Pseudo
@Mixin(targets = "appeng.crafting.CraftingLink", remap = false)
public abstract class Ae2CraftingLifecycleMixin {
    @Shadow(remap = false) public abstract boolean isCanceled();

    @Inject(method = "markDone", at = @At("RETURN"), require = 0, remap = false)
    private void maicraft$observeNativeCompletion(CallbackInfo callback) {
        if (!isCanceled()) Ae2NativeCraftingCompletion.finished(this, true);
    }

    @Inject(method = "cancel", at = @At("RETURN"), require = 0, remap = false)
    private void maicraft$observeNativeCancellation(CallbackInfo callback) {
        if (isCanceled()) Ae2NativeCraftingCompletion.finished(this, false);
    }
}
