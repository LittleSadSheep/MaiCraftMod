// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.ClientHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏聊天通道：把一句话交给游戏的聊天输入发送，并读本地聊天栏确认回显。
 *
 * <p>发送就是玩家在聊天栏打字发送，走角色自己的网络连接；发没发出去看回显——
 * 这句话出现在本地聊天栏里才算说过，读不到回显不算失败也不算成功。
 * 只在控制循环的刻内调用。以 / 开头的是给游戏的命令：原版聊天界面按前缀分别走命令与聊天
 * 两个发送方法，这里按同样的前缀路由，命令以角色自己的权限交给服务器裁决。
 * 命令没有自己那条回显，命令的完成依据（提交后聊天栏冒出的反馈行）也在这里读。
 */
public final class ChatChannel {

    private static final Logger LOG = LoggerFactory.getLogger(ChatChannel.class);

    private final Supplier<PlayerContext> context;

    public ChatChannel(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** 把一句话提交给游戏的聊天输入；以 / 开头的按游戏命令发送，其余按聊天发送。 */
    public void send(String message) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            // 交出去之前就断掉：留一条日志，免得"交出去了"其实没交出去时无迹可查。
            LOG.warn("[maicraft-chat] 聊天通道没接上，这句话没有交出去：{}", message);
            return;
        }
        if (message.startsWith("/")) {
            // 原版聊天界面的路由：命令去掉斜杠走命令发送件，服务器按角色自己的权限裁决。
            LOG.info("[maicraft-chat] 提交命令：{}", message);
            current.localPlayer().connection.sendCommand(message.substring(1));
        } else {
            LOG.info("[maicraft-chat] 提交聊天：{}", message);
            current.localPlayer().connection.sendChat(message);
        }
    }

    /** 聊天栏到此刻为止一共出现过几条：发游戏命令前记下。聊天栏还没接上时为 0。 */
    public long mark() {
        ChatLog log = ClientHooks.chatLog();
        return log == null ? 0 : log.mark();
    }

    /** 记号之后聊天栏新出现的话，按先后：发完游戏命令后读服务器回了什么。 */
    public List<String> shownSince(long mark) {
        ChatLog log = ClientHooks.chatLog();
        return log == null ? List.of() : log.shownSince(mark);
    }

    /** 这句话是否已经出现在本地聊天栏里（回显）。 */
    public boolean appearsInChat(String message) {
        ChatLog log = ClientHooks.chatLog();
        return log != null && log.showedUp(message);
    }

}
