// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

/**
 * 发聊天消息的入口：把一句话交给游戏的聊天输入，像玩家在聊天栏打字发送一样。
 * 只提交，发没发出去由聊天回显确认，不在这里冒充成功。
 */
public interface SendsChatMessage {

    /** 把一句话提交给游戏的聊天输入，对全体玩家可见。 */
    void send(String message);
}
