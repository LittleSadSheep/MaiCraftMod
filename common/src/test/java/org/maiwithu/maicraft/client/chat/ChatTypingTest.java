// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.chat;

import com.google.gson.JsonParser;

public final class ChatTypingTest {
    public static void main(String[] args) {
        check(parse("{\"text\":\"  /home   base  \"}").text().equals("/home base"), "native normalization");
        check(parse("{\"text\":\"hello\"}").intervalMillis() == 100, "default pacing");
        for (String json : new String[]{"{}", "{\"text\":1}", "{\"text\":null}",
                "{\"text\":\"/\"}", "{\"text\":\"   \"}", "{\"text\":\"a\\nb\"}",
                "{\"text\":\"a\",\"typing_interval_ms\":50.5}",
                "{\"text\":\"a\",\"typing_interval_ms\":49}",
                "{\"text\":\"a\",\"typing_interval_ms\":1001}",
                "{\"text\":\"a\",\"typing_interval_ms\":\"100\"}",
                "{\"text\":\"a\",\"typing_interval_ms\":null}"}) rejects(() -> parse(json));
        rejects(() -> new ChatMessage("x".repeat(257), 100));
        rejects(() -> new ChatMessage("\uD800", 100));
        rejects(() -> new ChatMessage("\u00A7cHi", 100));
        check(new ChatMessage("x".repeat(256), 100).text().length() == 256, "native length boundary");

        var typing = new ChatTyping(new ChatMessage("你👩‍💻e\u0301", 100));
        typing.resume(0);
        check(typing.totalCharacters() == 3, "Chinese, joined emoji and combining accent are whole graphemes");
        check(typing.advance(99_000_000L) == null, "initial visible dwell");
        check(typing.advance(100_000_000L).equals("你"), "first character");
        check(typing.advance(100_000_000L) == null, "no duplicate same-time typing");
        check(typing.advance(9_000_000_000L).equals("你👩‍💻"), "slow frame adds only one character");
        check(!typing.readyToSubmit(9_000_000_000L), "incomplete text cannot send");
        typing.resume(20_000_000_000L);
        check(typing.advance(20_000_000_000L) == null, "resume retains a dwell");
        check(typing.advance(20_100_000_000L).equals("你👩‍💻e\u0301"), "resume preserves draft");
        check(!typing.readyToSubmit(20_349_000_000L), "finished draft remains visible");
        typing.claimSubmission(20_350_000_000L);
        check(typing.submissionAttempted(), "native dispatch is claimed first");
        typing.resume(30_000_000_000L);
        check(!typing.readyToSubmit(40_000_000_000L), "resume cannot replay a submitted message");
        check(typing.advance(40_000_000_000L) == null, "terminal typing never edits");
        rejectsState(() -> typing.claimSubmission(40_000_000_000L));
        System.out.println("ChatTypingTest: passed");
    }

    private static ChatMessage parse(String json) { return ChatMessage.parse(JsonParser.parseString(json).getAsJsonObject()); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid chat accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void rejectsState(Runnable action) {
        try { action.run(); throw new AssertionError("duplicate submission accepted"); }
        catch (IllegalStateException expected) { }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
