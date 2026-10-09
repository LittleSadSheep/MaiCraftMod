// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * 当前连接的玩家列表（Tab 列表）：按名字查在线玩家的编号。只在客户端线程调用。
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
}
