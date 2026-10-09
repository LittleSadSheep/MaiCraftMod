// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 界面通道的读端：把认领时的那一份界面绑在对象与编号上，经由游戏接口层的菜单入口
 * 做放回物品的点击与关闭。
 *
 * <p>每刻最多做一件事：点击与关闭都先看菜单入口上有没有没结清的旧事务，有就等；
 * 界面对象与编号绑在一起核对，复用同一编号的另一只箱不算还开着。
 */
public final class ClientMenuChannel implements MenuChannel {

    /** 关闭一次最多等多少刻；与界面会话的预算同量级，到点由会话按卡住收场。 */
    private static final int CLOSE_TIMEOUT_TICKS = 100;

    private final Supplier<PlayerContext> contexts;
    /** 认领时绑定的界面：对象引用加容器编号，两者都对上才算还是这一份。 */
    private final AbstractContainerMenu claimedMenu;
    private final int claimedContainerId;
    private PendingMenuAction closing;

    /** 绑定当前打开的界面；没有打开的容器界面时为 null。 */
    public static ClientMenuChannel claimCurrent(Supplier<PlayerContext> contexts) {
        PlayerContext context = contexts.get();
        if (context == null) return null;
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        if (menu == context.localPlayer().inventoryMenu) return null;
        return new ClientMenuChannel(contexts, menu);
    }

    private ClientMenuChannel(Supplier<PlayerContext> contexts, AbstractContainerMenu claimedMenu) {
        this.contexts = Objects.requireNonNull(contexts);
        this.claimedMenu = claimedMenu;
        this.claimedContainerId = claimedMenu.containerId;
    }

    @Override
    public boolean stillOpen() {
        // 关闭进行中先逐刻推进它；结清（关上或到点失败）后按界面的真实去留回答。
        if (closing != null) {
            advanceClose();
        }
        PlayerContext context = contexts.get();
        if (context == null) return false;
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        // 对象与编号都要对上：复用同一编号的另一只箱不是同一份界面。
        return menu == claimedMenu && menu.containerId == claimedContainerId;
    }

    // 推进挂着的关闭；确认已完成（或原菜单已不在）就清掉关闭记录，让 stillOpen 按事实回答。
    private void advanceClose() {
        PlayerContext context = contexts.get();
        if (context == null || context.localPlayer().containerMenu != claimedMenu) {
            closing = null;
            return;
        }
        if (!closing.terminal()) {
            closing = context.menuActions().poll(context, closing);
        }
        if (closing.terminal()) closing = null;
    }

    @Override
    public boolean cursorCarrying() {
        AbstractContainerMenu menu = currentIfStillClaimed();
        return menu != null && !menu.getCarried().isEmpty();
    }

    @Override
    public void click(int slot, int button) {
        AbstractContainerMenu menu = currentIfStillClaimed();
        if (menu == null) return;
        PlayerContext context = contexts.get();
        MenuActions actions = context.menuActions();
        // 菜单入口上还有没结清的旧事务（包括上一次点击在等确认）时本刻不动手，等下一刻。
        if (actions.hasPendingTransaction()) return;
        actions.click(context, slot, button, ClickType.PICKUP, MenuConfirmation.stateChanged(), CLOSE_TIMEOUT_TICKS);
    }

    @Override
    public void requestClose() {
        PlayerContext context = contexts.get();
        if (currentIfStillClaimed() == null || context.menuActions().hasPendingTransaction()) return;
        closing = context.menuActions().close(context, CLOSE_TIMEOUT_TICKS);
    }

    // 界面还是认领的那一份时给出当前菜单，否则给 null；后续读数按"界面不在"处理。
    private AbstractContainerMenu currentIfStillClaimed() {
        PlayerContext context = contexts.get();
        if (context == null) return null;
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        return menu == claimedMenu && menu.containerId == claimedContainerId ? menu : null;
    }
}
