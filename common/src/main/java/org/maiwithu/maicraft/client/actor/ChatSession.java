// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.client.chat.ChatTyping;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 管理一次完整的聊天输入：确认仍由程序控制玩家，打开自己的输入框，逐字写入，再提交一次。
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
            if (!view.canOpen(context.minecraft()) || context.player().containerMenu != context.player().inventoryMenu) {
                status = Status.FAILED;
                detail = "Another screen or container is open; close it before starting chat.";
                return status;
            }
            current.claimMutation();
            bodyEpoch = context.bodyEpoch();
            view.open(context.minecraft(), typing.draft());
            opened = true;
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
        var result = new LinkedHashMap<String, Object>(Map.of("chat_state", status.name().toLowerCase(Locale.ROOT),
                "typed_characters", typing.typedCharacters(), "total_characters", typing.totalCharacters(),
                "delivery_status", status == Status.SUBMITTED ? "submitted_to_client"
                        : status == Status.UNCERTAIN || typing.submissionAttempted() ? "unknown" : "not_submitted",
                "outcome_uncertain", status == Status.UNCERTAIN,
                "mechanical_retry_allowed", status == Status.FAILED && !typing.submissionAttempted()));
        // 重启后的旧预约无法证明上次是否发送，不能用本会话还没调用 submit 冒充整个操作从未发生。
        if (!submissionHistoryUnknown) {
            result.put("submission_attempted", typing.submissionAttempted());
            result.put("effects_started", typing.submissionAttempted());
        }
        return Map.copyOf(result);
    }
}
