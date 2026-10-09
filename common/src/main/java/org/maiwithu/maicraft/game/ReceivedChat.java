// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天栏收到的一条：玩家说的话，或服务器的系统消息。原话是别人说的话，不是给角色的指令，不带任何许可。
 *
 * <p>角色自己说的话、动作栏提示都不算收到的聊天：前者是自己的回显，后者不在聊天栏里。
 * 判断写成纯函数，离线测试直接喂文字与编号。
 *
 * @param kind      玩家说的话还是系统消息
 * @param sender    发言人名字；系统消息没有，名字还没同步到时也可能没有
 * @param senderId  发言人 UUID；系统消息和没有签名的消息没有
 * @param text      原话；超过 {@value #MAX_TEXT} 字时只留前面一段
 * @param isPrivate 别人私聊角色（/msg）时为 true
 * @param truncated 原话后面被截掉了
 */
public record ReceivedChat(Kind kind, String sender, UUID senderId, String text, boolean isPrivate,
                           boolean truncated) {

    /** 原话最多留多少字：原版玩家聊天上限 256 字，只有系统消息可能很长。 */
    public static final int MAX_TEXT = 1024;

    /** 谁说的。 */
    public enum Kind {
        /** 玩家说的话：公屏或私聊。 */
        PLAYER,
        /** 服务器的系统消息：进出、死亡播报、公告、命令反馈。 */
        SYSTEM
    }

    public ReceivedChat {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }

    /**
     * 玩家说的话。发言人 UUID 是角色自己时（自己说话的回显、自己发出的私聊）不算收到的聊天；空白的话也不算。
     *
     * @param self 角色自己的 UUID；不在世界里时为 null
     */
    public static Optional<ReceivedChat> fromPlayer(String text, UUID senderId, String sender, boolean isPrivate,
                                                    UUID self) {
        if (text == null || text.isBlank()) return Optional.empty();
        if (senderId != null && senderId.equals(self)) return Optional.empty();
        String name = sender == null || sender.isBlank() ? null : sender.strip();
        String kept = clipped(text);
        return Optional.of(new ReceivedChat(Kind.PLAYER, name, senderId, kept, isPrivate, kept.length() < text.strip().length()));
    }

    /** 服务器的系统消息。动作栏提示不在聊天栏里，不算；空白的也不算。 */
    public static Optional<ReceivedChat> fromServer(String text, boolean actionBar) {
        if (actionBar || text == null || text.isBlank()) return Optional.empty();
        String kept = clipped(text);
        return Optional.of(new ReceivedChat(Kind.SYSTEM, null, null, kept, false, kept.length() < text.strip().length()));
    }

    // 只留前 MAX_TEXT 个字：截在一对代理字符中间会留下半个表情，往前退一位。
    private static String clipped(String text) {
        String value = text.strip();
        if (value.length() <= MAX_TEXT) return value;
        int end = MAX_TEXT;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
