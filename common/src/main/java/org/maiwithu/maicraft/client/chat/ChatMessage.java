// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.chat;

import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;

/** One native chat submission, validated before opening a screen or truncating any text. */
public record ChatMessage(String text, int intervalMillis) {
    public static final int MAX_LENGTH = 256;
    public static final int DEFAULT_INTERVAL = 100;

    public ChatMessage {
        if (text == null || text.length() > MAX_LENGTH)
            throw new IllegalArgumentException("text must contain 1-256 UTF-16 characters");
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0xA7
                || c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE))
            throw new IllegalArgumentException("text must be one line without control, formatting or unpaired surrogate characters");
        // Match ChatScreen's normalization so the visible draft matches the submitted content.
        text = StringUtils.normalizeSpace(text.trim());
        if (text.isEmpty() || text.equals("/"))
            throw new IllegalArgumentException("text must contain a message or a slash-prefixed command");
        if (intervalMillis < 50 || intervalMillis > 1_000)
            throw new IllegalArgumentException("typing_interval_ms must be an integer from 50 to 1000");
    }

    public boolean command() { return text.startsWith("/"); }

    public static ChatMessage parse(JsonObject parameters) {
        var text = parameters.get("text");
        if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("text is required and must be a string");
        int interval = DEFAULT_INTERVAL;
        if (parameters.has("typing_interval_ms")) {
            var value = parameters.get("typing_interval_ms");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("typing_interval_ms must be an integer from 50 to 1000");
            try { interval = value.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("typing_interval_ms must be an integer from 50 to 1000", invalid);
            }
        }
        return new ChatMessage(text.getAsString(), interval);
    }
}
