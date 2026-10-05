// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.perception;

import com.google.gson.JsonObject;

/**
 * 近窗刻率观察：用两次 MCP 观察之间的 game_time 差值还原世界刻速。
 * 单机模式下内置服务端与客户端共用主循环，游戏窗口失焦时操作系统限流后台进程，
 * 世界刻随之变慢——调用方据此区分「世界慢放」与「任务停滞」，不在限流期取消健康的任务。
 */
public final class TickRateObservation {
    /** 计算刻率的最小观察间隔：更短则刻率抖动到没有读数价值。 */
    private static final long MIN_WINDOW_MS = 1_000;
    /** 计算刻率的最大观察间隔：更长的间隔意味着切世界或进程重启，平均值不再代表当前刻速。 */
    private static final long MAX_WINDOW_MS = 300_000;

    private static long lastWallMillis;
    private static long lastGameTime = Long.MIN_VALUE;

    private TickRateObservation() {}

    /**
     * 记录本次观察并对照上一次给出刻率。首次观察、间隔出窗或世界时间回退时如实声明
     * 不可用及原因，不猜测刻率；时间由调用方注入以便纯函数直测。
     */
    public static synchronized JsonObject observe(long nowMillis, long gameTime) {
        JsonObject result = new JsonObject();
        long previousWall = lastWallMillis;
        long previousGameTime = lastGameTime;
        lastWallMillis = nowMillis;
        lastGameTime = gameTime;
        result.addProperty("game_time", gameTime);
        if (previousGameTime == Long.MIN_VALUE) {
            return unavailable(result, "first observation; query again after a while to compare");
        }
        long wallDelta = nowMillis - previousWall;
        long tickDelta = gameTime - previousGameTime;
        if (tickDelta < 0) {
            return unavailable(result, "world time moved backward; a world was switched or reloaded");
        }
        if (wallDelta < MIN_WINDOW_MS || wallDelta > MAX_WINDOW_MS) {
            return unavailable(result, "observation gap outside the averaging window");
        }
        result.addProperty("rate_available", true);
        result.addProperty("window_ms", wallDelta);
        result.addProperty("window_ticks", tickDelta);
        result.addProperty("recent_tps", Math.round(tickDelta * 10000.0 / wallDelta) / 10.0);
        return result;
    }

    private static JsonObject unavailable(JsonObject result, String reason) {
        result.addProperty("rate_available", false);
        result.addProperty("reason", reason);
        return result;
    }
}
