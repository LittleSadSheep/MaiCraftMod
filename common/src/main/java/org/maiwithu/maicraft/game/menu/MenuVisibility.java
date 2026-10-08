// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 管理菜单可见性的等待：界面必须对应当前菜单，并在改变后真正绘制过，自动化才可以点下一次。 */
public final class MenuVisibility {
    /** 最近一次真正绘制出来的界面与帧号；渲染事件从 Mixin 进入，这里只留这一个静态登记点。 */
    private record Rendered(Screen screen, long frame) {}

    // 用不可变的整体快照登记渲染帧，帧号单调前进，避免出现半更新的可见状态。
    private static final AtomicReference<Rendered> LAST_RENDERED =
            new AtomicReference<>(new Rendered(null, 0));
    private Screen observedScreen;
    private AbstractContainerMenu observedMenu;
    private long readyTick;
    private long afterFrame;

    /** 创造模式下也显示真实玩家背包及其槽位布局，确保点击对象与画面一致。 */
    public static final class PlayerInventoryScreen extends InventoryScreen {
        public PlayerInventoryScreen(LocalPlayer player) { super(player); }
    }

    public static boolean matches(Minecraft minecraft, AbstractContainerMenu menu) {
        return minecraft.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu;
    }

    public static boolean inventoryVisible(Minecraft minecraft, LocalPlayer player) {
        return matches(minecraft, player.inventoryMenu);
    }

    /** 判断当前界面是否允许角色在已有行走许可下继续移动。 */
    public static boolean worldInputAllowed(Screen screen) {
        // 聊天框保留：角色行走用输入信号而不是键盘事件，指定路线行走时可以继续聊天。
        // 床上的聊天界面必须保持静止等待自然醒；不能因继承普通聊天框而让旧导航继续移动。
        return screen == null || screen instanceof ChatScreen && !(screen instanceof InBedChatScreen);
    }

    /** 回到世界操作前，只接管鼠标及四格合成都已清空的普通玩家背包，避免关包时退料或掉物。 */
    public static boolean idlePlayerInventory(Minecraft minecraft, LocalPlayer player) {
        if (!(minecraft.screen instanceof InventoryScreen) || player.containerMenu != player.inventoryMenu
                || !inventoryVisible(minecraft, player) || !player.inventoryMenu.getCarried().isEmpty()
                || player.inventoryMenu.slots == null || player.inventoryMenu.slots.size() < 5) return false;
        for (int slot = 1; slot <= 4; slot++) if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        return true;
    }

    /** 界面实际渲染完成后才登记可见状态，游戏刻更新不能代替可见证据。 */
    public static void rendered(Screen screen) {
        Rendered current = LAST_RENDERED.get();
        LAST_RENDERED.set(new Rendered(screen, current.frame() + 1));
    }

    /** 当前登记过的渲染帧号；界面切换观察靠帧号判断“画过一帧”。 */
    private static long renderedFrame() {
        return LAST_RENDERED.get().frame();
    }

    private static Screen renderedScreen() {
        return LAST_RENDERED.get().screen();
    }

    boolean ready(PlayerContext context) {
        return ready(Minecraft.getInstance(), context);
    }

    boolean ready(Minecraft minecraft, PlayerContext context) {
        // 同时满足菜单对象匹配、最少等待刻数和新画面帧；有菜单对象但没显示出来不算就绪。
        observe(minecraft, context);
        return observedScreen != null && matches(minecraft, context.localPlayer().containerMenu)
                && context.clientTick() >= readyTick
                && renderedScreen() == observedScreen && renderedFrame() > afterFrame;
    }

    void observe(Minecraft minecraft, PlayerContext context) {
        // 换了界面或菜单对象，就重新等四刻并要求再绘制一帧，避免刚打开就连点。
        Screen screen = minecraft.screen;
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        if (screen != observedScreen || menu != observedMenu) {
            observedScreen = screen;
            observedMenu = menu;
            readyTick = context.clientTick() + 4;
            afterFrame = renderedFrame();
        }
    }

    void changed(PlayerContext context) {
        // 每次操作后至少再等两刻和一帧，让上一次结果有显示出来的机会。
        observe(Minecraft.getInstance(), context);
        readyTick = Math.max(readyTick, context.clientTick() + 2);
        afterFrame = renderedFrame();
    }

    void reset() {
        observedScreen = null;
        observedMenu = null;
        LAST_RENDERED.set(new Rendered(null, 0));
    }
}
