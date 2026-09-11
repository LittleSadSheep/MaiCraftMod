// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.connectivity.MekTransportOrigins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Records the actual cached inventory position at extraction, before later rotations can change it. */
@Pseudo
@Mixin(targets = "mekanism.common.tile.TileEntityLogisticalSorter", remap = false)
public abstract class MekSorterSourceMixin {
    @Coerce
    @WrapOperation(method = "getHomeInventory", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;getCapability()Ljava/lang/Object;"))
    private Object maicraft$observeHome(@Coerce Object cache, Operation<Object> original) {
        Object handler = original.call(cache);
        try {
            String api = "net.neoforged.neoforge.capabilities.BlockCapabilityCache";
            MekTransportOrigins.noteSource(this, (ServerLevel) NativeApi.call(cache, api, "level"),
                    (BlockPos) NativeApi.call(cache, api, "pos"), handler);
        } catch (RuntimeException | LinkageError ignored) { MekTransportOrigins.noteSource(this, null, null, null); }
        return handler;
    }

    @Coerce
    @WrapOperation(method = "onUpdateServer", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/common/tile/TileEntityLogisticalSorter;emitItemToTransporter(Lnet/neoforged/neoforge/items/IItemHandler;Lmekanism/common/lib/inventory/TransitRequest;Lmekanism/api/text/EnumColor;I)Lmekanism/common/lib/inventory/TransitRequest$TransitResponse;"))
    private Object maicraft$scopeEmission(@Coerce Object sorter, @Coerce Object destinationHandler,
                                         @Coerce Object request, @Coerce Object color, int minimum, Operation<Object> original) {
        MekTransportOrigins.Emission scope = MekTransportOrigins.beginEmission(sorter);
        Object response = null;
        try {
            response = original.call(sorter, destinationHandler, request, color, minimum);
            return response;
        } finally { MekTransportOrigins.finishEmission(scope, response); }
    }

    @WrapOperation(method = "onUpdateServer", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/common/lib/inventory/TransitRequest$TransitResponse;useAll()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack maicraft$scopeExtraction(@Coerce Object response, Operation<ItemStack> original) {
        MekTransportOrigins.Use scope = MekTransportOrigins.beginUse(response);
        boolean completed = false;
        try {
            ItemStack result = original.call(response);
            completed = true;
            return result;
        } finally { MekTransportOrigins.finishUse(scope, completed); }
    }
}
