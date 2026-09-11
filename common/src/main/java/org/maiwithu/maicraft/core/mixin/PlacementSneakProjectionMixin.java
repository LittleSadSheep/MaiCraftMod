// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.build.PlacementSneakProjection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Read projection is limited to the synchronous native-placement prediction for this player. */
@Mixin(LocalPlayer.class)
public abstract class PlacementSneakProjectionMixin {
    @ModifyReturnValue(method = "isShiftKeyDown", at = @At("RETURN"))
    private boolean maicraft$projectPlacementSneak(boolean actual) {
        return PlacementSneakProjection.project(this, actual);
    }
}
