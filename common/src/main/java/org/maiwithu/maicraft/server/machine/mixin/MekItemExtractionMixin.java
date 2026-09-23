// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.connectivity.MekTransportOrigins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** 原生响应只预测数量；只有这些真实提取返回值才能证明物品来源。 */
@Pseudo
@Mixin(targets = "mekanism.common.lib.inventory.HandlerTransitRequest$HandlerItemData", remap = false)
public abstract class MekItemExtractionMixin {
    @WrapOperation(method = "use", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack maicraft$observeExtraction(@Coerce Object handler, int slot, int requested, boolean simulate,
                                               Operation<ItemStack> original) {
        ItemStack result = original.call(handler, slot, requested, simulate);
        MekTransportOrigins.observedExtraction(handler, requested, result, simulate);
        return result;
    }
}
