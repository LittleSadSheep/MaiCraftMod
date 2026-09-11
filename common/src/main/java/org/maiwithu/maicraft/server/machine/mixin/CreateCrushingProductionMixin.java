// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.create.CreateGrindingCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity", remap = false)
public abstract class CreateCrushingProductionMixin {
    @WrapOperation(method = "tick", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/crusher/CrushingWheelControllerBlockEntity;applyRecipe()V"))
    private void maicraft$completion(@Coerce Object owner, Operation<Void> original) {
        var scope = CreateGrindingCapture.begin((BlockEntity) owner, true); boolean completed = false;
        try { original.call(owner); completed = true; }
        finally { CreateGrindingCapture.finish(scope, completed); }
    }

    @WrapOperation(method = "applyRecipe", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/processing/recipe/ProcessingInventory;clear()V"))
    private void maicraft$consume(@Coerce Object inventory, Operation<Void> original) {
        var receipt = CreateGrindingCapture.beforeClear((BlockEntity) (Object) this, inventory);
        original.call(inventory); CreateGrindingCapture.afterClear(receipt);
    }

    @WrapOperation(method = "applyRecipe", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/processing/recipe/StandardProcessingRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<ItemStack> maicraft$rolled(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        List<ItemStack> output = original.call(recipe, random);
        CreateGrindingCapture.rolled((BlockEntity) (Object) this, recipe); return output;
    }

    @WrapOperation(method = "applyRecipe", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/processing/recipe/ProcessingInventory;setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V"))
    private void maicraft$output(@Coerce Object inventory, int slot, ItemStack offered, Operation<Void> original) {
        var receipt = CreateGrindingCapture.beforeWrite((BlockEntity) (Object) this, inventory, slot, offered);
        original.call(inventory, slot, offered); CreateGrindingCapture.afterWrite(receipt);
    }
}
