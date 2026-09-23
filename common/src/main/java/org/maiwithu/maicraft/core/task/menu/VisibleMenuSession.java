package org.maiwithu.maicraft.core.task.menu;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 帮一项任务处理打开背包、关掉工作站菜单和最后收尾；记录是否曾使用界面、是否已经关好。 */
public final class VisibleMenuSession {
    private MenuReceipt closing;
    private MenuReceipt switching;
    private boolean used;
    private boolean closed;

    public boolean ready(LocalPlayerContext context) {
        used = true;
        return context.menus().ensureVisible(context);
    }

    /** 只有关闭现有工作站后，背包槽位编号才有效。 */
    public boolean inventoryReady(LocalPlayerContext context) {
        // 其他工作站的槽号不能当作玩家背包槽号；先关工作站，再显示背包并等待可操作。
        used = true;
        if (!settleSwitch(context)) return false;
        if (context.player().containerMenu != context.player().inventoryMenu) {
            switching = context.menus().close(context, 20);
            return false;
        }
        return ready(context);
    }

    /** 容器界面打开时，快捷栏物品仍无法在世界中使用。 */
    public boolean worldReady(LocalPlayerContext context) {
        // 回到世界里用物品前先关自己的菜单；如果用户打开了不相关的对话框，就等，不擅自关掉它。
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
        // 关闭结果确认后仍把这一刻留给界面切换，下一刻再让调用方继续操作。
        if (switching == null) return true;
        switching = context.menus().poll(context, switching);
        if (!switching.terminal()) return false;
        if (switching.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            throw new IllegalStateException("previous menu close was not confirmed: " + switching.detail());
        }
        switching = null;
        return false;
    }

    /** 等待上一次操作结果持续可见，并确认原生界面关闭完成。 */
    public boolean close(LocalPlayerContext context) {
        // 正常结束等关闭确认，没使用过界面则无需关闭；这个对象记住已关闭状态，重复调用不会再点。
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

    /** 权限撤销可能中断待处理点击，因此仅在普通空闲时关闭界面并不足够。 */
    public void cleanup(LocalPlayer player) {
        // 被取消时，旧点击可能还没确认，使用专门的任务结束关闭入口，不要求旧点击先成功。
        if (!used || closed) return;
        try {
            var context = ClientRuntime.requireContext(player);
            context.menus().closeForTaskBoundary(context, 20, "the owning GUI task ended");
        } catch (RuntimeException ignored) {
            // 权限失效后，由人工交接或角色权限撤销流程负责最终关闭界面。
        }
    }
}
