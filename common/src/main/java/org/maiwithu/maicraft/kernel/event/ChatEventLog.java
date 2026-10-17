// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.game.ReceivedChat;

/**
 * 聊天事件流：聊天栏收到的消息按游标往后读，没有新消息时可以等一会儿。宿主用 events(topic=chat) 读。
 *
 * <p>和任务事件流分开、各留最近 {@value #CAPACITY} 条：服务器上有人刷屏时，不能把目标的事件挤掉。
 * 游标、等待与重新同步的规则都在 {@link CursorLog}。Mod 不限流、不去重，收到什么记什么。
 */
public final class ChatEventLog {
    static final int CAPACITY = 256;

    private final CursorLog<ChatEvent> log = new CursorLog<>(CAPACITY, ChatEvent::cursor);

    /** 记一条收到的聊天并叫醒正在等的读者；返回带上序号的事件。 */
    public ChatEvent append(ReceivedChat chat) {
        return log.append(cursor -> new ChatEvent(cursor, chat));
    }

    /** 离开世界：清空、换一个新的流编号，下一个世界的聊天从新流开始。 */
    public void restart() {
        log.restart();
    }

    /** 当前的流编号。 */
    public String streamId() {
        return log.streamId();
    }

    /**
     * 读聊天；没有新消息时最多等 {@code waitMillis} 毫秒，期间来了消息或流换了就立即返回。
     *
     * @param expectedStream 读的一方上次拿到的流编号；第一次读时为 null
     * @param afterCursor    上次读到的游标；第一次读时为 0
     * @param limit          这次最多返回几条，至少 1
     * @param waitMillis     没有新消息时最多等多久；0 表示不等
     */
    public CursorLog.Page<ChatEvent> read(String expectedStream, long afterCursor, int limit, long waitMillis)
            throws InterruptedException {
        return log.read(expectedStream, afterCursor, event -> true, limit, waitMillis);
    }
}
