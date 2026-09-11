// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/** Cold inventory scaffolds use the same visible, receipt-confirmed selection flow as construction. */
final class EmbeddedBuildScaffoldSelection {
    private final FirstPersonActionGate gate = new FirstPersonActionGate();
    private EmbeddedBaritoneNavigator owner;
    private Item material;
    private int slot;
    private long lastTick = Long.MIN_VALUE;

    boolean select(LocalPlayerContext context, EmbeddedBaritoneNavigator navigator, LocalPlayer player) {
        if (owner != null && owner != navigator) cancel(owner);
        if (owner == null) {
            var choice = BuildPlacementRegistry.scaffoldChoice(player);
            if (choice == null) return false;
            owner = navigator; material = choice.item(); slot = choice.inventorySlot();
        }
        return advance(context, navigator);
    }

    boolean advance(LocalPlayerContext context, EmbeddedBaritoneNavigator navigator) {
        if (owner == null) return true;
        if (owner != navigator) { cancel(owner); return false; }
        long now = context.level().getGameTime();
        if (lastTick == now) return false;
        lastTick = now;
        var status = gate.select(context.player(), slot);
        if (gate.takeConfirmedSwap() != null) navigator.recordConfirmedNativeAction();
        if (status == FirstPersonActionGate.Status.FAILED) {
            String failure = gate.failure(); cancel(navigator);
            navigator.internalFailure("temporary scaffold selection was not confirmed: " + failure); return false;
        }
        if (status != FirstPersonActionGate.Status.READY) return false;
        boolean valid = context.player().getMainHandItem().is(material)
                && BuildPlacementRegistry.scaffoldUseAllowed(context.player(), context.player().getMainHandItem());
        cancel(navigator);
        return valid;
    }

    boolean pending() { return owner != null; }
    void cancel(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator) return;
        gate.reset(); owner = null; material = null; slot = -1;
    }
    void reset() { if (owner != null) cancel(owner); }
}
