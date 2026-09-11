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
@Mixin(targets = "com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity", remap = false)
public abstract class CreateMillstoneProductionMixin {
    @WrapOperation(method = "tick", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/millstone/MillstoneBlockEntity;process()V"))
    private void maicraft$completion(@Coerce Object owner, Operation<Void> original) {
        var scope = CreateGrindingCapture.begin((BlockEntity) owner, false); boolean completed = false;
        try { original.call(owner); completed = true; }
        finally { CreateGrindingCapture.finish(scope, completed); }
    }

    @WrapOperation(method = "process", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void maicraft$consume(ItemStack stack, int amount, Operation<Void> original) {
        var receipt = CreateGrindingCapture.beforeShrink((BlockEntity) (Object) this, stack, amount);
        original.call(stack, amount); CreateGrindingCapture.afterShrink(receipt);
    }

    @WrapOperation(method = "process", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/millstone/MillingRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<ItemStack> maicraft$rolled(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        List<ItemStack> output = original.call(recipe, random);
        CreateGrindingCapture.rolled((BlockEntity) (Object) this, recipe); return output;
    }

    @WrapOperation(method = {"process", "lambda$process$1"}, require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/ItemHandlerHelper;insertItemStacked(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack maicraft$output(@Coerce Object inventory, ItemStack offered, boolean simulate, Operation<ItemStack> original) {
        var receipt = CreateGrindingCapture.beforeInsert((BlockEntity) (Object) this, inventory, offered, simulate);
        ItemStack remainder = original.call(inventory, offered, simulate);
        CreateGrindingCapture.afterInsert(receipt, remainder); return remainder;
    }
}
