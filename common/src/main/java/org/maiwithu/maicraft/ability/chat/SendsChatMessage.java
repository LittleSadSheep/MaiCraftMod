// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

/**
 * 发聊天消息的入口：把一句话交给游戏的聊天输入，像玩家在聊天栏打字发送一样。
 * 只提交，发没发出去由聊天回显确认，不在这里冒充成功。
 */
public interface SendsChatMessage {

    /**
     * 把一句话提交给游戏的聊天输入，对全体玩家可见。
     *
     * @return 真的交给了聊天输入为 true；角色还没进世界、聊天通道没接上时交不出去，为 false
     */
    boolean send(String message);
}
