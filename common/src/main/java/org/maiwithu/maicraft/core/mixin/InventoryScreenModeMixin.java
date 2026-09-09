// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 自动任务打开的背包在创造模式下仍显示玩家槽位，避免被原版换成创造物品选择器；玩家正常打开的界面照旧。 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenModeMixin {
    @Redirect(method = {"init", "containerTick"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasInfiniteItems()Z"))
    private boolean maicraft$keepPlayerInventory(MultiPlayerGameMode gameMode) {
        return !((Object) this instanceof MenuVisibility.PlayerInventoryScreen) && gameMode.hasInfiniteItems();
    }
}
