// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地聊天栏的记录端：游戏往聊天栏加一条消息，这里记下它的文字与时刻，
 * 供发话的确认方读"这句话出现了没有"。只记文字，不给别人用；保留窗口外的旧消息丢弃。
 *
 * <p>实例在启动时创建并登记到 Mixin 的静态登记点；聊天栏每加一条消息由 Mixin 转过来。
 * 调用都发生在客户端线程，记录不额外加锁。
 */
public final class ChatLog {

    /** 一条刚出现的聊天消息：文字、出现时刻（毫秒时钟）与这是记下的第几条（从 1 数）。 */
    public record Shown(String text, long shownMillis, long sequence) {}

    // 聊天确认只看最近一小会儿：窗口外的旧消息对"刚发的话出现了没有"没有意义。
    private static final int MAX_LINES = 128;
    private static final long RETENTION_MILLIS = 10_000L;

    private List<Shown> lines = List.of();
    /** 一共记过几条：只增不减，旧消息丢了也不回退，发命令前记下它做记号。 */
    private long shownTotal;

    /** 聊天栏加了一条消息：记下文字与时刻。 */
    public void shown(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Shown> kept = new ArrayList<>(lines);
        kept.add(new Shown(text, now, ++shownTotal));
        lines = prune(kept, now);
    }

    /** 到此刻为止一共记过几条：发游戏命令前记下，之后按它取服务器回的话。 */
    public long mark() {
        return shownTotal;
    }

    /** 记号之后出现、还在保留窗口里的消息文字，按出现先后。 */
    public List<String> shownSince(long mark) {
        List<String> texts = new ArrayList<>();
        for (Shown line : retainRecent(lines, System.currentTimeMillis())) {
            if (line.sequence() > mark) {
                texts.add(line.text());
            }
        }
        return List.copyOf(texts);
    }

    /** 给定的话最近有没有在聊天栏里出现过（整句包含即可，服务端可能加了 <名字> 前缀）。 */
    public boolean showedUp(String message) {
        long now = System.currentTimeMillis();
        for (Shown line : retainRecent(lines, now)) {
            if (line.text().contains(message)) {
                return true;
            }
        }
        return false;
    }


    // 窗口外与超量的旧消息丢弃：纯函数，离线测试直接喂列表。
    static List<Shown> prune(List<Shown> lines, long nowMillis) {
        List<Shown> kept = new ArrayList<>();
        for (Shown line : lines) {
            if (nowMillis - line.shownMillis() <= RETENTION_MILLIS) {
                kept.add(line);
            }
        }
        while (kept.size() > MAX_LINES) {
            kept.remove(0);
        }
        return List.copyOf(kept);
    }

    // 读取时按当前时刻过滤；写入与读取用同一毫秒时钟。
    static List<Shown> retainRecent(List<Shown> lines, long nowMillis) {
        return prune(lines, nowMillis);
    }
}
