// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.client.chat.ChatTyping;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 管理一次完整的聊天输入：确认控制权，结清并关闭挡路界面，打开自己的输入框，逐字写入，再提交一次。
 * 人关掉、修改或接管输入框时取消自动提交；提交时抛错则记录结果未知，不能自动重发。
 */
public final class ChatSession {
    public enum Status { TYPING, SUBMITTED, CANCELLED, FAILED, UNCERTAIN }
    interface View {
        boolean canOpen(Minecraft minecraft);
        void open(Minecraft minecraft, String draft);
        boolean active();
        String text();
        void write(String draft);
        void submit();
        void close();
    }

    private final ChatTyping typing;
    private final View view;
    private final BooleanSupplier beforeSubmit;
    private Status status = Status.TYPING;
    private String detail = "Typing in the game chat box.";
    private long bodyEpoch = -1;
    private boolean opened;
    private boolean submissionHistoryUnknown;
    // 记录本次原生收尾，暂停恢复也沿用同一请求，避免重复关箱或反复调用页面退出。
    private MenuReceipt menuClose;
    private Screen closingScreen;
    private int screenCloseAttempts;
    private long screenCloseDeadline;
    private boolean screenExitFailed;

    public ChatSession(ChatMessage message, BooleanSupplier beforeSubmit) {
        this(message, new ChatScreenView(), beforeSubmit);
    }
    ChatSession(ChatMessage message, View view, BooleanSupplier beforeSubmit) {
        typing = new ChatTyping(message);
        this.view = view;
        this.beforeSubmit = Objects.requireNonNull(beforeSubmit, "durable chat submission boundary");
    }

    // 每刻先核对玩家、世界和输入框是否仍属于这次任务，再使用本刻的一次操作机会。
    // SUBMITTED 只表示原版客户端已经接收提交，不表示服务器收到或命令执行成功。
    public Status tick(LocalPlayerContext context, long now) {
        if (status != Status.TYPING) return status;
        if (!(context instanceof DefaultLocalPlayerContext current))
            throw new IllegalArgumentException("chat requires a native local-player context");
        current.requireSubmissionAuthority();
        if (bodyEpoch != -1 && bodyEpoch != context.bodyEpoch()) return cancel("The player/world changed before submission.");
        if (opened && (!view.active() || !typing.draft().equals(view.text())))
            return cancel("The chat box was closed, replaced or edited by the player; automation did not submit it.");
        if (!context.mutationAvailable()) return status;
        if (!opened) {
            // 等物品同步 -> 原生关容器或页面 -> 继续同一条聊天；关界面不另开任务，也不提前预约发送。
            if (bodyEpoch == -1) bodyEpoch = context.bodyEpoch();
            if (!prepareScreen(current)) return status;
            current.claimMutation();
            view.open(context.minecraft(), typing.draft());
            opened = true;
            detail = "Typing in the game chat box.";
            typing.resume(now);
            return status;
        }
        if (typing.readyToSubmit(now)) {
            // 草稿完整显示后才预约发送；等待父任务与提交编号落盘期间保留草稿，不提前发消息。
            try {
                if (!beforeSubmit.getAsBoolean()) return status;
            } catch (RuntimeException unavailable) {
                // 旧编号或无法核对的保存状态都不能放行重发；这份标记本身也不能证明服务器收到过消息。
                submissionHistoryUnknown = true;
                status = Status.UNCERTAIN;
                detail = "Chat submission history could not authorize a new send. Inspect the task and chat history before retrying.";
                view.close();
                opened = false;
                return status;
            }
            current.claimMutation();
            typing.claimSubmission(now);
            status = Status.UNCERTAIN;
            detail = "Native chat submission began; its result is unknown. Do not resend automatically.";
            try {
                view.submit();
                status = Status.SUBMITTED;
                detail = "Submitted through native chat input; server delivery or command success is not confirmed.";
            } finally { view.close(); opened = false; }
            return status;
        }
        String draft = typing.advance(now);
        if (draft != null) { current.claimMutation(); view.write(draft); }
        return status;
    }

    /** 已授权聊天被界面挡住时由执行器收尾；容器物品交给游戏返还，关闭确认前不打开聊天框。 */
    private boolean prepareScreen(DefaultLocalPlayerContext context) {
        if (menuClose != null && menuClose.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            menuClose = context.menus().poll(context, menuClose);
            if (!menuClose.terminal()) return false;
            // 真正的原生关闭失败仍须如实报告，不能强改菜单或把不确定返料当作已完成。
            if (menuClose.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                status = Status.FAILED;
                detail = "Native menu closure could not be confirmed before chat: " + menuClose.detail();
                return false;
            }
        }
        if (context.menus().hasPendingTransaction()) {
            detail = "Waiting for the existing menu transaction before continuing chat.";
            return false;
        }
        if (context.player().containerMenu != context.player().inventoryMenu
                || context.minecraft().screen instanceof AbstractContainerScreen<?>) {
            // 箱子、工作台和玩家背包都走原生关菜单，光标物品及合成余料按游戏规则处理，不手动清空。
            menuClose = context.menus().close(context, 40);
            detail = "Closing the native menu before continuing this chat.";
            return false;
        }
        if (!view.canOpen(context.minecraft())) {
            Screen blocker = context.minecraft().screen;
            // 暂停菜单、旧聊天框及模组页面各调用自己的退出逻辑；同一页面尚未退出时只等待，不反复关闭。
            if (blocker != null && blocker != closingScreen) {
                context.claimMutation();
                closingScreen = blocker;
                screenCloseAttempts++;
                screenCloseDeadline = context.tickRevision() + 40;
                blocker.onClose();
            } else if (blocker != null && context.tickRevision() >= screenCloseDeadline) {
                // 页面拒绝原生退出时给出实际失败，不能让无总期限的聊天永远卡在同一个页面。
                screenExitFailed = true;
                status = Status.FAILED;
                detail = "The screen did not exit after native closure before chat: " + blocker.getClass().getSimpleName();
                return false;
            }
            detail = "Closing the blocking screen before continuing this chat.";
            return false;
        }
        closingScreen = null;
        return true;
    }

    /**
     * 临时让出控制权时保留已打出的进度，关掉自己的输入框；恢复后继续同一份草稿。
     * 如果人已经接管输入框，就取消这次自动输入，不重新接管。
     */
    public void suspend() {
        if (status == Status.TYPING && opened && !view.active()) {
            cancel("The player took over the chat draft before the task paused.");
            return;
        }
        view.close();
        opened = false;
    }

    // 只把尚在输入的任务改为取消；已经提交或结果未知的状态必须保留，免得上层以为可以重发。
    public Status cancel(String reason) {
        if (status == Status.TYPING) { status = Status.CANCELLED; detail = reason; }
        view.close();
        opened = false;
        return status;
    }

    public Status status() { return status; }
    public String detail() { return detail; }
    public Map<String, Object> evidence() {
        // 容器收尾不确定也必须单独保留；没有发送消息并不能证明鼠标物品已经正确返还。
        boolean menuUncertain = menuClose != null && (menuClose.status() == MenuReceipt.Status.PENDING
                || menuClose.status() == MenuReceipt.Status.UNCERTAIN
                || menuClose.status() == MenuReceipt.Status.DIVERGED);
        var result = new LinkedHashMap<String, Object>(Map.of("chat_state", status.name().toLowerCase(Locale.ROOT),
                "typed_characters", typing.typedCharacters(), "total_characters", typing.totalCharacters(),
                "delivery_status", status == Status.SUBMITTED ? "submitted_to_client"
                        : status == Status.UNCERTAIN || typing.submissionAttempted() ? "unknown" : "not_submitted",
                "outcome_uncertain", status == Status.UNCERTAIN || menuUncertain,
                "mechanical_retry_allowed", status == Status.FAILED && !typing.submissionAttempted() && !menuUncertain && !screenExitFailed));
        // 自动清理与消息提交分开记账；等待、失败或取消时保留真实关界面回执，不能冒称消息已发送。
        if (screenCloseAttempts > 0 || menuClose != null) {
            var preparation = new LinkedHashMap<String, Object>();
            preparation.put("screen_close_attempts", screenCloseAttempts);
            if (closingScreen != null) preparation.put("screen_class", closingScreen.getClass().getSimpleName());
            if (menuClose != null) {
                preparation.put("menu_close_state", menuClose.status().name().toLowerCase(Locale.ROOT));
                preparation.put("menu_close_detail", menuClose.detail());
            }
            result.put("gui_preparation", Map.copyOf(preparation));
        }
        // 重启后的旧预约无法证明上次是否发送，不能用本会话还没调用 submit 冒充整个操作从未发生。
        if (!submissionHistoryUnknown) {
            result.put("submission_attempted", typing.submissionAttempted());
            result.put("effects_started", typing.submissionAttempted());
        }
        return Map.copyOf(result);
    }
}
