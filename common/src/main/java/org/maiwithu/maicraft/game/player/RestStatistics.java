// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.OptionalInt;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;

/**
 * 角色多久没睡了：只读请求原版的个人统计（离上次休息过了多少刻），只认服务器真正发来的休息计数，
 * 不把客户端缓存里的默认零、也不把世界日期当成睡眠记录。每三十秒请求一次，不打开统计界面，不碰玩家输入。
 * 统计包经 Mixin 在客户端线程喂进来；角色上床就作废旧计数，等新的统计。
 */
public final class RestStatistics {

    private static final long REQUEST_INTERVAL = 30 * 20;
    /** 统计超过这么久没更新就不再算数。 */
    private static final long MAX_AGE = 60 * 20;

    private int ticksSinceRest = -1;
    private long observedAt = -1;
    private long previousTick = -1;
    private long lastSleepTick = -1;
    private long nextRequestAt;

    /** 每刻看一眼：在床上就作废旧计数；到点就请求一次统计。断线、发不出去时只作废，不打断别的事。 */
    public void tick(LocalPlayer player) {
        long now = player.level().getGameTime();
        observeClock(now);
        if (player.isSleeping()) {
            sleeping(now);
            return;
        }
        if (now >= nextRequestAt) {
            nextRequestAt = now + REQUEST_INTERVAL;
            try {
                if (player.connection == null) {
                    invalidate();
                    return;
                }
                player.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
            } catch (RuntimeException unavailable) {
                invalidate();
            }
        }
    }

    /** 服务器发来的统计包：只读同一包里的个人休息项；别的统计更新不延长旧休息计数的有效期。 */
    public void received(LocalPlayer player, ClientboundAwardStatsPacket packet) {
        if (player == null || player.level() == null) return;
        long now = player.level().getGameTime();
        observeClock(now);
        if (player.isSleeping()) {
            sleeping(now);
            return;
        }
        var rest = Stats.CUSTOM.get(Stats.TIME_SINCE_REST);
        if (!packet.stats().containsKey(rest)) return;
        int value = packet.stats().getInt(rest);
        // 已经看到睡过了：上床前发出的旧请求带回来的高计数不能重新算成"好几天没睡"；留两秒同步余量。
        if (value < 0 || lastSleepTick >= 0 && value > now - lastSleepTick + 40) {
            invalidate();
            return;
        }
        ticksSinceRest = value;
        observedAt = now;
        nextRequestAt = now + REQUEST_INTERVAL;
    }

    /** 此刻有效的"离上次休息多少刻"；没收到过、太久没更新、刚睡过时为空。 */
    public OptionalInt ticksSinceRest(long now) {
        if (ticksSinceRest < 0 || observedAt < 0 || now - observedAt > MAX_AGE) return OptionalInt.empty();
        return OptionalInt.of(ticksSinceRest);
    }

    /** 最近一次收到休息计数的刻；没有为 -1。 */
    public long observedAt() {
        return observedAt;
    }

    /** 换身体、换世界、断线时清空：个人统计不跨身体用。 */
    public void clear() {
        ticksSinceRest = -1;
        observedAt = previousTick = lastSleepTick = -1;
        nextRequestAt = 0;
    }

    private void sleeping(long now) {
        lastSleepTick = now;
        nextRequestAt = now;
        invalidate();
    }

    // 世界时间往回走了（换世界、读档）：旧计数作废重来。
    private void observeClock(long now) {
        if (previousTick >= 0 && now < previousTick) clear();
        previousTick = now;
    }

    private void invalidate() {
        ticksSinceRest = -1;
        observedAt = -1;
    }
}
