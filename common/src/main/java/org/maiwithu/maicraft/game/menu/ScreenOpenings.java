// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 界面打开记录：每刻开头看一眼角色开着的界面变没变，变了就记下是哪一刻出现的、谁开的。
 *
 * <p>谁开的按出现前自动化有没有刚动过手来判：自动化拿着角色、而且最近 {@value #TASK_WINDOW_TICKS} 刻内
 * 真的向游戏出过手（右键方块、点开背包），这份界面算任务开的；人拿着角色时出现的，或自动化这阵子没出过手时出现的
 * （人按 E 开背包、服务器自己弹出来的），算人开的。进世界、换身体时已经开着的，说不清谁开的。
 *
 * <p>只在客户端线程用：菜单入口每刻推进时看一次，读界面的地方读之前也先看一次，读到的总是眼前这一份。
 */
public final class ScreenOpenings {

    /** 出手之后多少刻内出现的界面还算这一下开出来的。C 玩家常识：右键到服务端回开界面的包一般几刻，留足网络抖动的余量。 */
    static final int TASK_WINDOW_TICKS = 40;

    private LocalPlayer player;
    private Screen screen;
    private AbstractContainerMenu menu;
    private ScreenOpening current;

    /**
     * 看一眼眼前的界面：和上次看到的一样就什么都不做；换了一份就记下这一刻与谁开的；什么都没开就清掉记录。
     * 换了身体（进世界、重生、过门）后第一眼看到已经开着的界面，记成说不清谁开的。
     */
    void observe(PlayerContext context) {
        LocalPlayer self = context == null ? null : context.localPlayer();
        if (self == null) return;
        Screen nowScreen = Minecraft.getInstance().screen;
        AbstractContainerMenu nowMenu = self.containerMenu;
        boolean newBody = self != player;
        if (!newBody && nowScreen == screen && nowMenu == menu) return;
        player = self;
        screen = nowScreen;
        menu = nowMenu;
        if (nowScreen == null && nowMenu == self.inventoryMenu) {
            current = null;
            return;
        }
        if (newBody) {
            current = new ScreenOpening(nowScreen, nowMenu, -1, context.clientTick(), ScreenOpening.OpenedBy.UNKNOWN);
            return;
        }
        boolean automation = context.input() != null && context.input().automationOwnsControls();
        current = new ScreenOpening(nowScreen, nowMenu, nowMenu.getStateId(), context.clientTick(),
                openedBy(automation, context.clientTick(), context.lastInteractionTick()));
    }

    /** 眼前开着的界面的记录（读之前先看一眼）；什么都没开时为空。 */
    Optional<ScreenOpening> current(PlayerContext context) {
        observe(context);
        return Optional.ofNullable(current);
    }

    /**
     * 谁开的：自动化拿着角色、而且出手不久就出现的，是任务开的；其余都不是任务开的。
     *
     * @param automationOwnsControls 界面出现这一刻自动化是否拿着角色
     * @param now                    界面出现这一刻的客户端刻
     * @param lastInteraction        自动化最近一次真的出手的刻；没出过手为 Long.MIN_VALUE
     */
    static ScreenOpening.OpenedBy openedBy(boolean automationOwnsControls, long now, long lastInteraction) {
        if (!automationOwnsControls) return ScreenOpening.OpenedBy.PLAYER;
        // 用"出手的刻不早于窗口起点"来比，没出过手的 Long.MIN_VALUE 自然落在窗口外，不会算溢出。
        return lastInteraction >= now - TASK_WINDOW_TICKS ? ScreenOpening.OpenedBy.TASK : ScreenOpening.OpenedBy.PLAYER;
    }
}
