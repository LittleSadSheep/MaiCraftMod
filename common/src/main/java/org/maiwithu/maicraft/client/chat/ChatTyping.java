// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 只计算现在应该显示多少文字，不操作输入框。中文、组合表情和附加音符按完整可见字符逐个显示。
 * 每次最多增加一个字符；最后一字显示后再停留四分之一秒，才允许提交。
 */
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

    /**
     * 时间到了才增加一个完整字符。即使上一帧卡了很久，也不会一下补出所有漏掉的字符。
     */
    public String advance(long now) {
        if (submissionAttempted || typed == ends.size() || now - nextAt < 0) return null;
        typed++;
        nextAt = now + (typed == ends.size() ? SUBMIT_DELAY : interval());
        return draft();
    }

    public boolean readyToSubmit(long now) {
        return !submissionAttempted && typed == ends.size() && now - nextAt >= 0;
    }

    /**
     * 先记下已经尝试提交，再交给实际输入框发送；后续恢复或重复调用都不能再发一次。
     */
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
