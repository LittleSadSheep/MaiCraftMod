// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** Native first-person action submission and read-only reconciliation. */
public interface NativeActionPort {
    NativeActionReceipt startBreaking(LocalPlayerContext context, BlockHitResult hit, int timeoutTicks);

    NativeActionReceipt cancelBreaking(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt continueBreaking(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt useBlock(
            LocalPlayerContext context,
            InteractionHand hand,
            BlockHitResult hit,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt useItem(
            LocalPlayerContext context,
            InteractionHand hand,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt releaseUsingItem(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt selectHotbar(LocalPlayerContext context, int slot, int timeoutTicks);

    NativeActionReceipt creativeSetSlot(
            LocalPlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks);

    NativeActionReceipt submitProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt attack(

            LocalPlayerContext context,
            Entity target,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt interact(
            LocalPlayerContext context,
            Entity target,
            InteractionHand hand,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt poll(LocalPlayerContext context, NativeActionReceipt receipt);
}
