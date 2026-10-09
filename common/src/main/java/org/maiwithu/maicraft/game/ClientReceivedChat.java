// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;

/**
 * 加载器转来的聊天事件读成收到的聊天：认出发言人、私聊和角色自己的回显。
 *
 * <p>两个加载器给的东西不一样多：有签名的玩家消息带原话与发言人 UUID；没有签名的（例如命令方块的 /say）
 * 只有聊天栏显示的整行和消息类型。名字没给时按 UUID 从当前连接的玩家列表里查，再不行用消息类型里的名字。
 * 只在客户端线程调用。
 */
public final class ClientReceivedChat {

    private ClientReceivedChat() {}

    /**
     * 玩家说的话（公屏或私聊）。
     *
     * @param line     聊天栏显示的整行，带发言人名字
     * @param content  不带名字的原话；没有签名的消息拿不到，为 null
     * @param senderId 发言人 UUID；没有签名的消息拿不到，为 null
     * @param sender   发言人名字；加载器没给时为 null
     * @param chatType 消息类型：公屏、私聊……
     */
    public static Optional<ReceivedChat> player(Minecraft minecraft, Component line, Component content,
                                                UUID senderId, String sender, ChatType.Bound chatType) {
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        String name = sender != null ? sender : nameOf(minecraft, senderId);
        if (name == null && chatType != null) {
            // 没有签名的消息只剩消息类型里的名字（服务器给它起的显示名）。
            name = chatType.name().getString();
        }
        // 别人用 /msg 对角色说的话是"收到的私聊"这种消息类型；自己发出的私聊回显按发言人是自己滤掉。
        boolean isPrivate = chatType != null && chatType.chatType().is(ChatType.MSG_COMMAND_INCOMING);
        String text = (content != null ? content : line).getString();
        return ReceivedChat.fromPlayer(text, senderId, name, isPrivate, self);
    }

    /** 服务器的系统消息；动作栏提示不算聊天。 */
    public static Optional<ReceivedChat> system(Component message, boolean actionBar) {
        return ReceivedChat.fromServer(message.getString(), actionBar);
    }

    // 按 UUID 从当前连接的玩家列表里查名字；查不到（还没同步、已经下线）时为 null，不编一个。
    private static String nameOf(Minecraft minecraft, UUID senderId) {
        ClientPacketListener connection = minecraft.getConnection();
        if (senderId == null || connection == null) return null;
        PlayerInfo info = connection.getPlayerInfo(senderId);
        return info == null ? null : info.getProfile().getName();
    }
}
