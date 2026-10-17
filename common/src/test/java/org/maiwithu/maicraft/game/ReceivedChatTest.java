// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 收到的聊天：别人说的话照实收下，自己的回显、动作栏提示、空白消息不收，太长的截断并标出来。 */
class ReceivedChatTest {
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @Test
    void someoneElseSpeakingIsKeptWithTheirNameAndId() {
        ReceivedChat chat = ReceivedChat.fromPlayer("  麦麦你在干嘛  ", STEVE, "Steve", false, SELF).orElseThrow();

        assertEquals(ReceivedChat.Kind.PLAYER, chat.kind());
        assertEquals("Steve", chat.sender());
        assertEquals(STEVE, chat.senderId());
        assertEquals("麦麦你在干嘛", chat.text());
        assertFalse(chat.isPrivate());
        assertFalse(chat.truncated());
    }

    @Test
    void theCharactersOwnWordsAreNotReceivedChat() {
        // 自己说话的回显与自己发出的私聊，发言人都是自己。
        assertTrue(ReceivedChat.fromPlayer("我来了", SELF, "Mai", false, SELF).isEmpty());
        assertTrue(ReceivedChat.fromPlayer("悄悄话", SELF, "Mai", true, SELF).isEmpty());
    }

    @Test
    void unsignedMessagesWithoutAnIdAreStillKept() {
        // 命令方块的 /say 这类没有签名的消息：没有 UUID，名字可能也没有，照样收下，不当成自己的话。
        ReceivedChat chat = ReceivedChat.fromPlayer("[Server] 欢迎", null, " ", false, SELF).orElseThrow();

        assertNull(chat.senderId());
        assertNull(chat.sender());
    }

    @Test
    void actionBarAndBlankMessagesAreNotChat() {
        assertTrue(ReceivedChat.fromServer("床太远了", true).isEmpty());
        assertTrue(ReceivedChat.fromServer("   ", false).isEmpty());
        assertTrue(ReceivedChat.fromPlayer("", STEVE, "Steve", false, SELF).isEmpty());

        ReceivedChat joined = ReceivedChat.fromServer("Alex 加入了游戏", false).orElseThrow();
        assertEquals(ReceivedChat.Kind.SYSTEM, joined.kind());
        assertNull(joined.sender());
    }

    @Test
    void longTextIsCutAndMarkedWithoutSplittingACharacter() {
        String emoji = "😀";
        // 第 MAX_TEXT 个字落在一个表情的前半个上：往前退一位，不留下半个表情。
        String text = "a".repeat(ReceivedChat.MAX_TEXT - 1) + emoji + "后面还有";
        ReceivedChat chat = ReceivedChat.fromServer(text, false).orElseThrow();

        assertTrue(chat.truncated());
        assertEquals(ReceivedChat.MAX_TEXT - 1, chat.text().length());
        assertFalse(Character.isHighSurrogate(chat.text().charAt(chat.text().length() - 1)));
        assertFalse(ReceivedChat.fromServer("a".repeat(ReceivedChat.MAX_TEXT), false).orElseThrow().truncated());
    }
}
