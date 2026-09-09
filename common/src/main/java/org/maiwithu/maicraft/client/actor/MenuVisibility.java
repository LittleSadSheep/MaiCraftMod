// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 管理菜单可见性的等待：界面必须对应当前菜单，并在改变后真正绘制过，自动化才可以点下一次。 */
public final class MenuVisibility {
    private static Screen renderedScreen;
    private static long renderedFrame;
    private Screen observedScreen;
    private AbstractContainerMenu observedMenu;
    private long readyTick;
    private long afterFrame;

    /** Keeps the actual player inventory and its slot layout visible even in creative mode. */
    public static final class PlayerInventoryScreen extends InventoryScreen {
        public PlayerInventoryScreen(LocalPlayer player) { super(player); }
    }

    public static boolean matches(Minecraft minecraft, AbstractContainerMenu menu) {
        return minecraft.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu;
    }

    public static boolean inventoryVisible(Minecraft minecraft, LocalPlayer player) {
        return matches(minecraft, player.inventoryMenu);
    }

    /** Called after rendering the screen, never from a game tick. */
    public static void rendered(Screen screen) {
        renderedScreen = screen;
        renderedFrame++;
    }

    boolean ready(LocalPlayerContext context) {
        // 同时满足菜单对象匹配、最少等待刻数和新画面帧；有菜单对象但没显示出来不算就绪。
        observe(context);
        return observedScreen != null && matches(context.minecraft(), context.player().containerMenu)
                && context.tickRevision() >= readyTick
                && renderedScreen == observedScreen && renderedFrame > afterFrame;
    }

    void observe(LocalPlayerContext context) {
        // 换了界面或菜单对象，就重新等四刻并要求再绘制一帧，避免刚打开就连点。
        Screen screen = context.minecraft().screen;
        AbstractContainerMenu menu = context.player().containerMenu;
        if (screen != observedScreen || menu != observedMenu) {
            observedScreen = screen;
            observedMenu = menu;
            readyTick = context.tickRevision() + 4;
            afterFrame = renderedFrame;
        }
    }

    void changed(LocalPlayerContext context) {
        // 每次操作后至少再等两刻和一帧，让上一次结果有显示出来的机会。
        observe(context);
        readyTick = Math.max(readyTick, context.tickRevision() + 2);
        afterFrame = renderedFrame;
    }

    void reset() {
        observedScreen = null;
        observedMenu = null;
        renderedScreen = null;
    }
}
