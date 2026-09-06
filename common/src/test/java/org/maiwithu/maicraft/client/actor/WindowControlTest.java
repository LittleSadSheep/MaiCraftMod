package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import sun.misc.Unsafe;

/** Exercise cursor ownership without opening a native window or moving the user's mouse. */
public final class WindowControlTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe memory = (Unsafe) singleton.get(null);
        Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        minecraft.player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        minecraft.level = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        FakeMouse mouse = new FakeMouse(minecraft);
        assign(minecraft, "mouseHandler", mouse);
        assign(minecraft, "windowActive", true);
        ClientActorBoundary boundary = new ClientActorBoundary(minecraft);
        mouse.boundary = boundary;

        boundary.updateWindowControl(false);
        check(mouse.releases == 0 && mouse.grabs == 0, "human control does not touch the cursor");
        mouse.grabbed = true;
        boundary.updateWindowControl(true);
        check(!mouse.grabbed && mouse.releases == 1, "takeover releases a locked cursor once");
        minecraft.player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        boundary.updateWindowControl(true);
        check(mouse.releases == 1 && mouse.grabs == 0, "retained dimension handoff keeps the cursor released");
        boundary.updateWindowControl(false);
        check(mouse.grabbed && mouse.grabs == 1, "foreground handoff restores the previous mouse lock");

        mouse.grabbed = false;
        boundary.updateWindowControl(true);
        boundary.updateWindowControl(false);
        check(!mouse.grabbed && mouse.grabs == 1, "a cursor that was already free remains free");

        boundary.updateWindowControl(true);
        minecraft.screen = (MenuVisibility.PlayerInventoryScreen)
                memory.allocateInstance(MenuVisibility.PlayerInventoryScreen.class);
        minecraft.screen = null; // Native container close attempts grab before F8 completes handoff.
        mouse.grabMouse();
        check(boundary.preventsMouseGrab() && !mouse.grabbed, "menu cleanup retains the takeover cursor guard");
        boundary.updateWindowControl(false);
        check(mouse.grabs == 1 && !mouse.grabbed, "GUI cleanup cannot lock a cursor that was originally free");

        mouse.grabbed = true;
        boundary.updateWindowControl(true);
        assign(minecraft, "windowActive", false);
        boundary.updateWindowControl(false);
        check(!mouse.grabbed && mouse.grabs == 1, "background release cannot grab or focus the game");
        assign(minecraft, "windowActive", true);
        boundary.updateWindowControl(false);
        check(mouse.grabs == 1, "returning to the window does not perform a delayed cursor grab");

        mouse.grabbed = true;
        boundary.updateWindowControl(true);
        minecraft.screen = (PauseScreen) memory.allocateInstance(PauseScreen.class);
        boundary.updateWindowControl(false);
        check(mouse.grabs == 1 && minecraft.screen != null, "handoff does not dismiss a user's pause screen");

        minecraft.screen = null;
        mouse.grabbed = true;
        boundary.updateWindowControl(true);
        minecraft.level = null;
        minecraft.player = null;
        boundary.updateWindowControl(false);
        check(mouse.grabs == 1 && !mouse.grabbed, "disconnect clears takeover without locking the cursor");
        System.out.println("WindowControlTest: passed");
    }

    private static final class FakeMouse extends MouseHandler {
        ClientActorBoundary boundary;
        boolean grabbed;
        int grabs;
        int releases;

        FakeMouse(Minecraft minecraft) { super(minecraft); }
        @Override public boolean isMouseGrabbed() { return grabbed; }
        @Override public void grabMouse() {
            if (boundary != null && boundary.preventsMouseGrab()) return;
            grabs++;
            grabbed = true;
        }
        @Override public void releaseMouse() { releases++; grabbed = false; }
    }

    private static void assign(Minecraft minecraft, String name, Object value) throws Exception {
        Field field = Minecraft.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(minecraft, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
