// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.maiwithu.maicraft.server.machine.mekanism.MekProductionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Each target has one applicable shrink call; bridge methods and simulations cannot double count it. */
@Pseudo
@Mixin(targets = {"mekanism.api.recipes.inputs.InputHelper$1", "mekanism.api.recipes.inputs.InputHelper$3",
        "mekanism.api.recipes.inputs.InputHelper$ChemicalInputHandler"}, remap = false)
public abstract class MekInputProductionMixin {
    @WrapOperation(method = "use", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/inventory/IInventorySlot;shrinkStack(ILmekanism/api/Action;)I"))
    private int maicraft$itemInput(@Coerce Object slot, int requested, @Coerce Object action, Operation<Integer> original) {
        MekProductionCapture.Transfer capture = MekProductionCapture.beforeInput(slot, "mekanism.api.inventory.IInventorySlot", "getStack", action);
        int actual = original.call(slot, requested, action);
        MekProductionCapture.afterInput(capture, requested, actual);
        return actual;
    }

    @WrapOperation(method = "use", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/fluid/IExtendedFluidTank;shrinkStack(ILmekanism/api/Action;)I"))
    private int maicraft$fluidInput(@Coerce Object tank, int requested, @Coerce Object action, Operation<Integer> original) {
        MekProductionCapture.Transfer capture = MekProductionCapture.beforeInput(tank, "mekanism.api.fluid.IExtendedFluidTank", "getFluid", action);
        int actual = original.call(tank, requested, action);
        MekProductionCapture.afterInput(capture, requested, actual);
        return actual;
    }

    @WrapOperation(method = "use", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/api/chemical/IChemicalTank;shrinkStack(JLmekanism/api/Action;)J"))
    private long maicraft$chemicalInput(@Coerce Object tank, long requested, @Coerce Object action, Operation<Long> original) {
        MekProductionCapture.Transfer capture = MekProductionCapture.beforeInput(tank, "mekanism.api.chemical.IChemicalTank", "getStack", action);
        long actual = original.call(tank, requested, action);
        MekProductionCapture.afterInput(capture, requested, actual);
        return actual;
    }
}
