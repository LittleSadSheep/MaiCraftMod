// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import java.util.Objects;
import org.maiwithu.maicraft.game.ReceivedChat;

/**
 * 聊天事件流里的一条：收到的一条聊天和它在流里的序号。
 *
 * @param cursor 在聊天事件流里的序号，从 1 开始递增
 * @param chat   收到的那条聊天
 */
public record ChatEvent(long cursor, ReceivedChat chat) {
    public ChatEvent {
        Objects.requireNonNull(chat, "chat");
        if (cursor < 1) throw new IllegalArgumentException("事件序号从 1 开始：" + cursor);
    }
}
