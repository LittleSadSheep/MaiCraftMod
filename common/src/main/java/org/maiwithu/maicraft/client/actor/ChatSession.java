// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.client.chat.ChatTyping;
import java.util.Locale;
import java.util.Map;

/** One owned chat draft, paced on leased client ticks and submitted at most once. */
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
    private Status status = Status.TYPING;
    private String detail = "Typing in the game chat box.";
    private long bodyEpoch = -1;
    private boolean opened;

    public ChatSession(ChatMessage message) { this(message, new ChatScreenView()); }
    ChatSession(ChatMessage message, View view) { typing = new ChatTyping(message); this.view = view; }

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

    /** A scheduler pause releases only our screen; resume opens the retained draft with a new dwell. */
    public void suspend() {
        if (status == Status.TYPING && opened && !view.active()) {
            cancel("The player took over the chat draft before the task paused.");
            return;
        }
        view.close();
        opened = false;
    }

    public Status cancel(String reason) {
        if (status == Status.TYPING) { status = Status.CANCELLED; detail = reason; }
        view.close();
        opened = false;
        return status;
    }

    public Status status() { return status; }
    public String detail() { return detail; }
    public Map<String, Object> evidence() {
        return Map.of("chat_state", status.name().toLowerCase(Locale.ROOT),
                "typed_characters", typing.typedCharacters(), "total_characters", typing.totalCharacters(),
                "submission_attempted", typing.submissionAttempted(), "effects_started", typing.submissionAttempted(),
                "delivery_status", status == Status.SUBMITTED ? "submitted_to_client"
                        : typing.submissionAttempted() ? "unknown" : "not_submitted",
                "outcome_uncertain", status == Status.UNCERTAIN,
                "mechanical_retry_allowed", status == Status.FAILED && !typing.submissionAttempted());
    }
}
