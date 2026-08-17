// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feed synchronized client block changes into the sparse live target index. */
@Mixin(Level.class)
public abstract class ClientLevelBlockChangeMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void maicraft$feedTargetIndex(
            BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if ((Object) this instanceof ClientLevel clientLevel) {
            TargetIndex.onBlockChange(clientLevel, pos, oldState, newState);
        }
    }
}
