package org.maiwithu.maicraft.core.task.menu;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** A task-owned visible menu, including confirmed completion and interrupted-task cleanup. */
public final class VisibleMenuSession {
    private MenuReceipt closing;
    private MenuReceipt switching;
    private boolean used;
    private boolean closed;

    public boolean ready(LocalPlayerContext context) {
        used = true;
        return context.menus().ensureVisible(context);
    }

    /** Inventory slot numbers are valid only after an existing workstation has been closed. */
    public boolean inventoryReady(LocalPlayerContext context) {
        used = true;
        if (!settleSwitch(context)) return false;
        if (context.player().containerMenu != context.player().inventoryMenu) {
            switching = context.menus().close(context, 20);
            return false;
        }
        return ready(context);
    }

    /** A hotbar item still cannot be used in the world while a container screen is open. */
    public boolean worldReady(LocalPlayerContext context) {
        if (!settleSwitch(context)) return false;
        if (DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)
                && context.player().containerMenu == context.player().inventoryMenu) {
            return context.mutationAvailable();
        }
        if (context.minecraft().screen != null
                && !MenuVisibility.matches(context.minecraft(), context.player().containerMenu)) return false;
        used = true;
        closed = false;
        switching = context.menus().close(context, 20);
        return false;
    }

    private boolean settleSwitch(LocalPlayerContext context) {
        if (switching == null) return true;
        switching = context.menus().poll(context, switching);
        if (!switching.terminal()) return false;
        if (switching.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            throw new IllegalStateException("previous menu close was not confirmed: " + switching.detail());
        }
        switching = null;
        return false;
    }

    /** Wait for the last operation to remain visible and the native close to be confirmed. */
    public boolean close(LocalPlayerContext context) {
        if (!used || closed) return true;
        if (closing == null) {
            closing = context.menus().close(context, 20);
            return false;
        }
        closing = context.menus().poll(context, closing);
        if (!closing.terminal()) return false;
        if (closing.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            throw new IllegalStateException("menu close was not confirmed: " + closing.detail());
        }
        closed = true;
        return true;
    }

    /** Revocation can interrupt a pending click, so ordinary idle-only close is insufficient. */
    public void cleanup(LocalPlayer player) {
        if (!used || closed) return;
        try {
            var context = ClientRuntime.requireContext(player);
            context.menus().closeForTaskBoundary(context, 20, "the owning GUI task ended");
        } catch (RuntimeException ignored) {
            // Human handoff and actor revocation own the final close when authority has gone.
        }
    }
}
