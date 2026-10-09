// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import java.util.List;

/**
 * 读聊天回显的只读入口：看一句话有没有在聊天栏出现（本地回显）。
 * 回显是发话完成与否的依据；读不到就是没确认，不当作没发出去，也不当作发成功。
 * 由游戏接口层实现；读别人的聊天与听消息归感知侧，不在这里。
 */
public interface ReadsChatEcho {

    /** 这句话是否已经出现在聊天栏里（本地回显）。 */
    boolean appearsInChat(String message);

    /** 聊天栏到此刻为止一共出现过几条：发游戏命令前记下；读不到聊天栏时为 0。 */
    long mark();

    /**
     * 记号之后聊天栏新出现的话，按先后。游戏命令没有"自己那条"的回显，命令发没发成、
     * 服务器回了什么（命令反馈行）都从这里读；读不到时为空。
     */
    List<String> shownSince(long mark);
}
