// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameRules;

/**
 * 死亡不掉落（keepInventory）这条游戏规则读出来是什么。
 *
 * <p>原版不把这条规则同步给客户端：只有本机跑着集成服务端（单人世界、开局域网的主机）时读得到真实值，
 * 连别人的服务器时读不到，只能靠调用方确认。读要在服务端线程上做：这一刻交出去，回来之前答"正在读"，
 * 读到后记几秒再重读，规则很少改，不必每刻都问。
 */
public final class KeepInventoryRule {

    /** 读到的样子。 */
    public enum Reading {
        /** 开着：死了身上的东西不掉。 */
        ON,
        /** 关着：死了东西掉在原地。 */
        OFF,
        /** 已经交给服务端线程去读，还没回来。 */
        READING,
        /** 连的是别人的服务器，客户端读不到这条规则。 */
        UNREADABLE
    }

    /** 读到的值记多久再重读（毫秒）：有人改了规则，几秒后就能看到。 */
    private static final long REFRESH_MILLIS = 5_000;

    /** 上一次是从哪个集成服务端读的：换了世界就作废重读。 */
    private IntegratedServer readFrom;
    private CompletableFuture<Boolean> pending;
    private Boolean known;
    private long readAtMillis;

    /** 此刻读到的样子；只在客户端线程调用。 */
    public Reading read() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            forget(null);
            return Reading.UNREADABLE;
        }
        if (server != readFrom) forget(server);
        long now = System.currentTimeMillis();
        settle(now);
        // 还没读过或读到的太旧：交给服务端线程读一次，回来之前照旧答上次读到的（第一次答"正在读"）。
        if (pending == null && (known == null || now - readAtMillis >= REFRESH_MILLIS)) {
            pending = server.submit(() -> server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY));
        }
        if (known == null) return Reading.READING;
        return known ? Reading.ON : Reading.OFF;
    }

    // 服务端线程读回来了就记下；读的时候出错（服务端正在关）当没读到，下次再读。
    private void settle(long now) {
        if (pending == null || !pending.isDone()) return;
        try {
            known = pending.join();
            readAtMillis = now;
        } catch (RuntimeException failed) {
            known = null;
        }
        pending = null;
    }

    // 换了世界或连到别人的服务器：上一个世界读到的值不能拿来用。
    private void forget(IntegratedServer server) {
        readFrom = server;
        pending = null;
        known = null;
        readAtMillis = 0;
    }
}
