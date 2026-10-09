// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

/**
 * 读聊天回显的只读入口：看一句话有没有在聊天栏出现（本地回显）。
 * 回显是发话完成与否的依据；读不到就是没确认，不当作没发出去，也不当作发成功。
 * 由游戏接口层实现；读别人的聊天与听消息归感知侧，不在这里。
 */
public interface ReadsChatEcho {

    /** 这句话是否已经出现在聊天栏里（本地回显）。 */
    boolean appearsInChat(String message);

    /**
     * 给定时刻之后聊天栏有没有出现过新的一行。游戏命令没有"自己那条"的回显，
     * 命令发没发成只能看提交后聊天栏里有没有冒出命令反馈行，这一条就是它的读端。
     */
    boolean anyLineAfter(long sinceMillis);
}
