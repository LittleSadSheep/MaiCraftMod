// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 当前连接的玩家列表（Tab 列表）：按名字查在线玩家的编号、按编号问在不在线。只在客户端线程调用。
 */
public final class OnlinePlayers {

    private OnlinePlayers() {}

    /**
     * 名字对应的玩家编号（UUID 字符串）。
     * 只认此刻在线、已同步到玩家列表里的人；不在线或还没同步时为空，不猜也不编。
     */
    public static Optional<String> idByName(Minecraft minecraft, String name) {
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(name);
        return info == null ? Optional.empty() : Optional.of(info.getProfile().getId().toString());
    }

    /**
     * 这个编号的玩家此刻在不在玩家列表里：看不见他不等于他下线了，走出能看见的范围时他照样在列表里。
     * 还没连上服务器时答不在。
     */
    public static boolean online(PlayerContext context, UUID id) {
        ClientPacketListener connection = context.connection();
        return connection != null && connection.getPlayerInfo(id) != null;
    }
}
