// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.build.PlacementPlayerProjection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 施工试算期间只替换指定玩家的潜行读取；实际按键仍由真实动作控制器持有。 */
@Mixin(LocalPlayer.class)
public abstract class PlacementSneakProjectionMixin {
    @ModifyReturnValue(method = "isShiftKeyDown", at = @At("RETURN"))
    private boolean maicraft$projectPlacementSneak(boolean actual) {
        return PlacementPlayerProjection.project(this, actual);
    }
}
