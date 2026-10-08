// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/** 等物品同步 -> 原生退出挡路界面 -> 继续原任务；菜单操作期间由调用方保留所需菜单。 */
public final class GuiPreparation {
    private static final int CLOSE_TICKS = 40;
    private final MenuActions menuActions;
    private final InteractionSender sender;
    private final PlayerInput playerInput;
    private PendingMenuAction closing;
    private Screen screen;
    private String screenClass;
    private long screenDeadline;
    private int screenAttempts;
    private String failure;
    private boolean screenUncertain;
    private boolean returnPending;
    private boolean closureSettled;

    /** 三个入口都由启动时注入同一个实例：关闭与轮询发到本菜单入口，挂起的菜单协议问交互提交方。 */
    public GuiPreparation(MenuActions menuActions, InteractionSender sender, PlayerInput playerInput) {
        this.menuActions = menuActions;
        this.sender = sender;
        this.playerInput = playerInput;
    }

    /** 原生退出的真实失败带着收尾事实交给任务，不能被公共异常外壳冒称为代码内部错误。 */
    public static final class Failure extends IllegalStateException {
        private final Map<String, Object> evidence;
        private final boolean uncertain;
        private Failure(String detail, Map<String, Object> evidence, boolean uncertain) {
            super(detail); this.evidence = evidence; this.uncertain = uncertain;
        }
        public Map<String, Object> evidence() { return evidence; }
        public boolean uncertain() { return uncertain; }
    }

    /** 移动可保留聊天框，聊天发送则须先退出旧草稿；其他页面均先执行原生退出。 */
    public boolean ready(PlayerContext context, boolean allowChat) {
        var player = context.localPlayer();
        // 床上界面由原版睡眠生命周期管理，onClose 会发送起床包；等待自然醒，不能把它当成普通挡路页。
        if (player.isSleeping() || Minecraft.getInstance().screen instanceof InBedChatScreen) {
            playerInput.releaseAll(player);
            return false;
        }
        if (closing != null && closing.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
            closing = menuActions.poll(context, closing);
            if (!closing.terminal()) return false;
            if (closing.status() != PendingMenuAction.Status.CONFIRMED_APPLIED)
                throw failed("Native menu closure was not confirmed: " + closing.detail());
        }
        if (menuActions.hasPendingTransaction()) return false;
        // 菜单协议和普通槽位点击分别等确认；持续挖掘不在此阻塞，以免导航无法继续完成同一次破坏。
        if (sender.hasPendingMenuTransaction()) return false;
        Screen current = Minecraft.getInstance().screen;
        returnPending = closing != null && !closureSettled
                && closing.status() == PendingMenuAction.Status.CONFIRMED_APPLIED
                && player.containerMenu == player.inventoryMenu && current == null
                && (!player.inventoryMenu.getCarried().isEmpty() || inventoryGridOccupied(player));
        if (returnPending) {
            // 页面可能先消失、退料同步稍后才到；等待同一次原生返还，不能对未清空的旧合成格反复发关闭。
            if (context.clientTick() >= closing.deadlineTick())
                throw failed("Native inventory return was not confirmed after menu closure");
            return false;
        }
        if (player.containerMenu != player.inventoryMenu || current instanceof AbstractContainerScreen<?>
                || !player.inventoryMenu.getCarried().isEmpty() || inventoryGridOccupied(player)) {
            // 容器、背包和鼠标物品都交给原版关闭流程返还，不逐格清空或伪造库存变化。
            closing = menuActions.close(context, CLOSE_TICKS);
            closureSettled = false;
            return false;
        }
        if (current == null || allowChat && current instanceof ChatScreen) {
            screen = null;
            closureSettled = true;
            return true;
        }
        // 只在自动化仍持有当前角色时退出暂停或模组页面；同一退出请求未完成前不重复触发。
        playerInput.releaseAll(player);
        if (current != screen) {
            screen = current;
            screenClass = current.getClass().getSimpleName();
            screenDeadline = context.clientTick() + CLOSE_TICKS;
            screenAttempts++;
            try { current.onClose(); }
            catch (RuntimeException unavailable) {
                screenUncertain = true;
                throw failed("Native screen exit threw before completion: " + screenClass);
            }
        } else if (context.clientTick() >= screenDeadline) {
            throw failed("The screen did not exit after native closure: " + screenClass);
        }
        return false;
    }

    static boolean inventoryGridOccupied(LocalPlayer player) {
        // 角色仍带着背包合成余料时也先走原生退料，再盘点后续施工、附魔或投料的可用材料。
        var inventory = player.inventoryMenu;
        if (inventory.slots == null || inventory.slots.size() < 5) return false;
        for (int slot = 1; slot <= 4; slot++) if (!inventory.getSlot(slot).getItem().isEmpty()) return true;
        return false;
    }

    private Failure failed(String detail) {
        // 真正无法退出时保留事实并有界结束；不能强改页面或让无总期限任务永远等待。
        failure = detail;
        return new Failure(detail, evidence(), uncertain());
    }

    public boolean failed() { return failure != null; }
    public boolean uncertain() {
        // 已提交但未确认的关箱与消息、放置等主动作分别结算，不能用未发送主动作掩盖返料未知。
        return returnPending || screenUncertain || closing != null
                && (closing.status() == PendingMenuAction.Status.PENDING
                || closing.status() == PendingMenuAction.Status.UNCERTAIN
                || closing.status() == PendingMenuAction.Status.DIVERGED);
    }
    public Map<String, Object> evidence() {
        if (closing == null && screenAttempts == 0) return Map.of();
        var data = new LinkedHashMap<String, Object>();
        data.put("screen_close_attempts", screenAttempts);
        if (screenClass != null) data.put("screen_class", screenClass);
        if (screenUncertain) data.put("screen_close_uncertain", true);
        if (returnPending) data.put("inventory_return_state", "pending");
        if (closing != null) {
            data.put("menu_close_state", closing.status().name().toLowerCase(Locale.ROOT));
            data.put("menu_close_detail", closing.detail());
        }
        if (failure != null) data.put("failure_detail", failure);
        return Map.copyOf(data);
    }
}
