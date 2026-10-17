// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/** 到服务器一个来回要几刻：按连接里自己的延迟（毫秒）折成游戏刻，读不到为 -1。等服务器同步结果时按它多等。 */
public final class NetworkDelay {

    private NetworkDelay() {}

    public static int roundTripTicks(PlayerContext context) {
        var connection = context.connection();
        var self = context.localPlayer();
        var entry = connection == null || self == null ? null : connection.getPlayerInfo(self.getUUID());
        return entry == null ? -1 : (Math.max(0, entry.getLatency()) + 49) / 50;
    }
}
