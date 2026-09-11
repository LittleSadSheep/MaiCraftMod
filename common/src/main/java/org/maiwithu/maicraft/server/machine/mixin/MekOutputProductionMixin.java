// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.mekanism.MekProductionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Capture the actual remainder that OutputHelper discards, after the native insertion executes once. */
@Pseudo
@Mixin(targets = "mekanism.api.recipes.outputs.OutputHelper", remap = false)
public abstract class MekOutputProductionMixin {
    @WrapOperation(method = "handleOutput(Lmekanism/api/inventory/IInventorySlot;Lnet/minecraft/world/item/ItemStack;I)V",
            require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/inventory/IInventorySlot;insertItem(Lnet/minecraft/world/item/ItemStack;Lmekanism/api/Action;Lmekanism/api/AutomationType;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack maicraft$itemOutput(@Coerce Object slot, ItemStack offered, @Coerce Object action,
                                               @Coerce Object automation, Operation<ItemStack> original) {
        MekProductionCapture.Transfer capture = MekProductionCapture.beforeOutput(offered, action);
        ItemStack remainder = original.call(slot, offered, action, automation);
        MekProductionCapture.afterOutput(capture, remainder);
        return remainder;
    }

    @Coerce
    @WrapOperation(method = "handleOutput(Lmekanism/api/fluid/IExtendedFluidTank;Lnet/neoforged/neoforge/fluids/FluidStack;I)V",
            require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/fluid/IExtendedFluidTank;insert(Lnet/neoforged/neoforge/fluids/FluidStack;Lmekanism/api/Action;Lmekanism/api/AutomationType;)Lnet/neoforged/neoforge/fluids/FluidStack;"))
    private static Object maicraft$fluidOutput(@Coerce Object tank, @Coerce Object offered, @Coerce Object action,
                                             @Coerce Object automation, Operation<Object> original) {
        MekProductionCapture.Transfer capture = MekProductionCapture.beforeOutput(offered, action);
        Object remainder = original.call(tank, offered, action, automation);
        MekProductionCapture.afterOutput(capture, remainder);
        return remainder;
    }

    @Coerce
    @WrapOperation(method = "handleOutput(Lmekanism/api/chemical/IChemicalTank;Lmekanism/api/chemical/ChemicalStack;I)V",
            require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/chemical/IChemicalTank;insert(Lmekanism/api/chemical/ChemicalStack;Lmekanism/api/Action;Lmekanism/api/AutomationType;)Lmekanism/api/chemical/ChemicalStack;"))
    private static Object maicraft$chemicalOutput(@Coerce Object tank, @Coerce Object offered, @Coerce Object action,
                                                @Coerce Object automation, Operation<Object> original) {
        MekProductionCapture.Transfer capture = MekProductionCapture.beforeOutput(offered, action);
        Object remainder = original.call(tank, offered, action, automation);
        MekProductionCapture.afterOutput(capture, remainder);
        return remainder;
    }
}
