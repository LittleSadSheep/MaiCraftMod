// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the automation inventory keeps the server's inventory slots instead of the item picker. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenModeMixin {
    @Redirect(method = {"init", "containerTick"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasInfiniteItems()Z"))
    private boolean maicraft$keepPlayerInventory(MultiPlayerGameMode gameMode) {
        return !((Object) this instanceof MenuVisibility.PlayerInventoryScreen) && gameMode.hasInfiniteItems();
    }
}
