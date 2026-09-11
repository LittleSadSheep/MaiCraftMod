// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.PressProductionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity", remap = false)
public abstract class CreatePressProductionMixin {
    @Inject(method = "tryProcessOnBelt", at = @At("HEAD"), require = 0, remap = false)
    private void maicraft$capturePressInput(@Coerce Object transported, List<ItemStack> output, boolean simulate,
                                          CallbackInfoReturnable<Boolean> callback) {
        PressProductionCapture.begin((BlockEntity) (Object) this, transported, output, simulate);
    }

    @Inject(method = "tryProcessOnBelt", at = @At("RETURN"), require = 0, remap = false)
    private void maicraft$recordPressOutput(@Coerce Object transported, List<ItemStack> output, boolean simulate,
                                          CallbackInfoReturnable<Boolean> callback) {
        PressProductionCapture.finish((BlockEntity) (Object) this, output, simulate, callback.getReturnValueZ());
    }
}
