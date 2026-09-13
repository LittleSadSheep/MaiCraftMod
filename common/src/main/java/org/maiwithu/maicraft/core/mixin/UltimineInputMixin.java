// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineInputLease;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only FTB's menu-modifier read is projected; its original HUD, shape handler and permission rules still execute. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbultimine.client.FTBUltimineClient", remap = false)
public abstract class UltimineInputMixin {
    @ModifyReturnValue(method = "isMenuSneaking()Z", at = @At("RETURN"), remap = false, require = 0)
    private boolean maicraft$ownedMenuModifier(boolean actual) { return UltimineInputLease.menuModifier(actual); }
    @Inject(method = "clientTick", at = @At("HEAD"), remap = false, require = 0)
    private void maicraft$releaseExpiredKey(CallbackInfo callback) { UltimineInputLease.beforeNativeTick(); }
    @Inject(method = "clientTick", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$observeNativePanel(CallbackInfo callback) { UltimineInputLease.nativeTickFinished(); }
    @Inject(method = "renderGameOverlay", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$observeVisiblePanel(CallbackInfo callback) { UltimineInputLease.hudRendered(); }
}
