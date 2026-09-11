// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.machine.connectivity.MekTransportCapture;
import org.maiwithu.maicraft.server.machine.connectivity.MekTransportOrigins;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Mekanism 10.7 observer; native calls run exactly once and retain their return value. */
@Pseudo
@Mixin(targets = "mekanism.common.content.network.transmitter.LogisticalTransporterBase", remap = false)
public abstract class MekTransportDeliveryMixin {
    @Inject(method = "createInsertStack", at = @At("RETURN"), require = 0, remap = false)
    private void maicraft$createdTransport(long source, @Coerce Object color, CallbackInfoReturnable<Object> callback) {
        MekTransportOrigins.created(this, callback.getReturnValue());
    }

    @Coerce
    @WrapOperation(method = "getCapForSide", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;getCapability()Ljava/lang/Object;"))
    private Object maicraft$capturePullSource(@Coerce Object cache, Operation<Object> original) {
        Object handler = original.call(cache);
        try {
            String api = "net.neoforged.neoforge.capabilities.BlockCapabilityCache";
            Object rawLevel = NativeApi.call(cache, api, "level");
            if (rawLevel instanceof ServerLevel level) {
                MekTransportOrigins.noteSource(this, level, (BlockPos) NativeApi.call(cache, api, "pos"), handler);
            }
        } catch (RuntimeException | LinkageError unsupported) { MekTransportOrigins.noteSource(this, null, null, null); }
        return handler;
    }

    @Coerce
    @WrapOperation(method = "onUpdateServer", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/common/content/network/transmitter/LogisticalTransporterBase;insert(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/core/BlockPos;Lmekanism/common/lib/inventory/TransitRequest;Lmekanism/api/text/EnumColor;ZI)Lmekanism/common/lib/inventory/TransitRequest$TransitResponse;"))
    private Object maicraft$observePullEmission(@Coerce Object transmitter, @Coerce Object outputter, BlockPos source,
                                              @Coerce Object request, @Coerce Object color, boolean emit, int minimum,
                                              Operation<Object> original) {
        MekTransportOrigins.Emission scope = emit ? MekTransportOrigins.beginEmission(this) : null;
        Object response = null;
        try {
            response = original.call(transmitter, outputter, source, request, color, emit, minimum);
            return response;
        } finally { MekTransportOrigins.finishEmission(scope, response); }
    }

    @WrapOperation(method = "onUpdateServer", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lmekanism/common/lib/inventory/TransitRequest$TransitResponse;useAll()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack maicraft$observePullExtraction(@Coerce Object response, Operation<ItemStack> original) {
        MekTransportOrigins.Use scope = MekTransportOrigins.beginUse(response);
        boolean completed = false;
        try {
            ItemStack result = original.call(response);
            completed = true;
            return result;
        } finally { MekTransportOrigins.finishUse(scope, completed); }
    }

    @WrapOperation(method = "onUpdateServer", require = 0, remap = false,
            slice = @Slice(from = @At(value = "INVOKE", ordinal = 0,
                    target = "Lmekanism/common/content/transporter/TransporterStack;isFinal(Lmekanism/common/content/network/transmitter/LogisticalTransporterBase;)Z"),
                    to = @At(value = "INVOKE",
                            target = "Lmekanism/common/lib/inventory/TransitRequest$SimpleTransitRequest;addToInventory(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/items/IItemHandler;IZ)Lmekanism/common/lib/inventory/TransitRequest$TransitResponse;")),
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lmekanism/common/content/transporter/TransporterStack;itemStack:Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack maicraft$captureForwardStack(@Coerce Object transported, Operation<ItemStack> original) {
        ItemStack result = original.call(transported);
        MekTransportCapture.prepare(this, transported, result);
        return result;
    }

    @Coerce
    @WrapOperation(method = "onUpdateServer", require = 0, remap = false,
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/lib/inventory/TransitRequest$SimpleTransitRequest;addToInventory(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/neoforged/neoforge/items/IItemHandler;IZ)Lmekanism/common/lib/inventory/TransitRequest$TransitResponse;"))
    private Object maicraft$observeDelivery(@Coerce Object request, Level level, BlockPos destination,
                                          @Coerce Object handler, int minimum, boolean forceHome, Operation<Object> original) {
        MekTransportCapture.Frame frame = MekTransportCapture.take(this);
        Object response = original.call(request, level, destination, handler, minimum, forceHome);
        MekTransportCapture.finish(frame, level, destination, forceHome, response);
        return response;
    }
}
