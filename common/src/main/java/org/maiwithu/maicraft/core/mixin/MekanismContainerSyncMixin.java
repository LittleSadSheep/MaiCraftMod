// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismFilterSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional observation only: missing/changed Mekanism signatures disable evidence, never startup. */
@Pseudo
@Mixin(targets = "mekanism.common.inventory.container.MekanismContainer", remap = false)
public abstract class MekanismContainerSyncMixin {
    @Inject(method = "handleWindowProperty(SZ)V", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$sorterBoolean(short property, boolean value, CallbackInfo callback) {
        MekanismFilterSync.received((AbstractContainerMenu) (Object) this, property, false);
    }
    @Inject(method = "handleWindowProperty(S[B)V", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$sorterFilters(short property, byte[] value, CallbackInfo callback) {
        MekanismFilterSync.received((AbstractContainerMenu) (Object) this, property, true);
    }
}
