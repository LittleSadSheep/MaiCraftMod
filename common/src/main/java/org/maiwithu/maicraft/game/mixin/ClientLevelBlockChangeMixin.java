// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.world.BlockScanService;

import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将同步后的客户端方块变化交给方块扫描服务：刷新目标索引，并留一份最近变化位置供读侧优先核查。 */
@Mixin(Level.class)
public abstract class ClientLevelBlockChangeMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void maicraft$feedTargetIndex(
            BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        // 服务端世界也走这个方法；只把客户端世界的变化交给扫描服务。
        if ((Object) this instanceof ClientLevel clientLevel) {
            BlockScanService scans = ClientHooks.blockScans();
            if (scans != null) scans.onBlockChange(clientLevel, pos, oldState, newState);
        }
    }
}
