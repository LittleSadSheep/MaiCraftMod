// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.chat;

import java.util.List;
import java.util.regex.Pattern;

/** Bounded, tick-driven typing: never split a grapheme or burst after a slow background frame. */
public final class ChatTyping {
    private static final Pattern CHARACTER = Pattern.compile("\\X");
    private static final long SUBMIT_DELAY = 250_000_000L;
    private final ChatMessage message;
    private final List<Integer> ends;
    private int typed;
    private long nextAt;
    private boolean submissionAttempted;

    public ChatTyping(ChatMessage message) {
        this.message = message;
        ends = CHARACTER.matcher(message.text()).results().map(match -> match.end()).toList();
    }

    public void resume(long now) {
        nextAt = now + (typed == ends.size() ? SUBMIT_DELAY : interval());
    }

    /** Returns the new full draft, or null when this tick should leave the input unchanged. */
    public String advance(long now) {
        if (submissionAttempted || typed == ends.size() || now - nextAt < 0) return null;
        typed++;
        nextAt = now + (typed == ends.size() ? SUBMIT_DELAY : interval());
        return draft();
    }

    public boolean readyToSubmit(long now) {
        return !submissionAttempted && typed == ends.size() && now - nextAt >= 0;
    }

    /** Claim before entering the native handler; an exception must never permit another send. */
    public void claimSubmission(long now) {
        if (!readyToSubmit(now)) throw new IllegalStateException("chat submission is not ready or was already attempted");
        submissionAttempted = true;
    }

    public String draft() { return typed == 0 ? "" : message.text().substring(0, ends.get(typed - 1)); }
    public int typedCharacters() { return typed; }
    public int totalCharacters() { return ends.size(); }
    public boolean submissionAttempted() { return submissionAttempted; }
    private long interval() { return message.intervalMillis() * 1_000_000L; }
}
