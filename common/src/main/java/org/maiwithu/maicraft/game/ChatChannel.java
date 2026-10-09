// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.ClientHooks;

/**
 * 游戏聊天通道：把一句话交给游戏的聊天输入发送，并读本地聊天栏确认回显。
 *
 * <p>发送就是玩家在聊天栏打字发送，走角色自己的网络连接；发没发出去看回显——
 * 这句话出现在本地聊天栏里才算说过，读不到回显不算失败也不算成功。
 * 只在控制循环的刻内调用。命令（以 / 开头）不归这里：能力一侧已经拦下，通道本身不区分。
 */
public final class ChatChannel {

    private final Supplier<PlayerContext> context;

    public ChatChannel(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** 把一句话提交给游戏的聊天输入，对全体玩家可见。 */
    public void send(String message) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return;
        }
        current.localPlayer().connection.sendChat(message);
    }

    /** 这句话是否已经出现在本地聊天栏里（回显）。 */
    public boolean echoed(String message) {
        ChatLog log = ClientHooks.chatLog();
        return log != null && log.showedUp(message);
    }
}
