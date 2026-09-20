// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** 根据当前连接估计槽位同步的等待窗口；画面先变不是服务端已经完成整笔操作的证明。 */
public final class MenuSynchronization {
    private MenuSynchronization() {}

    public static int windowTicks(LocalPlayerContext context) {
        var info = context.connection().getPlayerInfo(context.player().getUUID());
        long latencyMillis = info == null ? 0L : Math.max(0, info.getLatency());
        int roundTripTicks = (int) ((latencyMillis + 49L) / 50L);
        return Math.max(3, roundTripTicks + 2);
    }
}
