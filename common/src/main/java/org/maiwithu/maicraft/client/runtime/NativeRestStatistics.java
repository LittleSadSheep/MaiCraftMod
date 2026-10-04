// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;

/** 只读请求原版个人统计；只认实际收到的休息计数，不把客户端缓存默认零或世界日期当作睡眠记录。 */
public final class NativeRestStatistics {
    private static final long REQUEST_INTERVAL = 30 * 20, MAX_AGE = 60 * 20;
    private final SleepReminder reminder;
    private int ticksSinceRest = -1;
    private long observedAt = -1, previousTick = -1, lastSleepTick = -1, nextRequestAt;

    public NativeRestStatistics(SleepReminder reminder) { this.reminder = reminder; }

    /** 原生上床状态每刻复核；统计每三十秒请求一次，不打开统计界面，也不接管玩家输入。 */
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
                if (player.connection == null) { invalidate(now, "rest_stat_connection_unavailable"); return; }
                player.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
            } catch (RuntimeException unavailable) {
                // 断线期间的只读请求失败不能打断施工、自卫或原有受伤通知；失去依据就只撤下本条提醒。
                invalidate(now, "rest_stat_request_failed");
                return;
            }
        }
        if (ticksSinceRest < 0 || observedAt < 0 || now - observedAt > MAX_AGE) {
            reminder.unavailable(now, "rest_stat_unavailable_or_stale");
            return;
        }
        var level = player.level();
        var dimension = level.dimensionType();
        reminder.observe(new SleepReminder.Observation(now, ticksSinceRest, observedAt,
                level.getDayTime(), dimension.bedWorks(), dimension.natural()));
    }

    /** 原版处理统计包后读取同一包中的个人休息项；其他统计的更新不能延长旧休息计数的有效期。 */
    public void received(LocalPlayer player, ClientboundAwardStatsPacket packet) {
        long now = player.level().getGameTime();
        observeClock(now);
        if (player.isSleeping()) { sleeping(now); return; }
        var rest = Stats.CUSTOM.get(Stats.TIME_SINCE_REST);
        if (!packet.stats().containsKey(rest)) return;
        int value = packet.stats().getInt(rest);
        // 已观察到入睡后，旧请求的高计数不能重新唤醒“多日未睡”；留两秒同步余量，再等待新统计。
        if (value < 0 || lastSleepTick >= 0 && value > now - lastSleepTick + 40) {
            invalidate(now, "rest_stat_inconsistent_with_observed_sleep");
            return;
        }
        ticksSinceRest = value;
        observedAt = now;
        nextRequestAt = now + REQUEST_INTERVAL;
    }

    private void sleeping(long now) {
        lastSleepTick = now;
        nextRequestAt = now;
        invalidate(now, "native_sleep_observed");
    }

    private void observeClock(long now) {
        if (previousTick >= 0 && now < previousTick) {
            clear();
            reminder.unavailable(now, "rest_observation_clock_changed");
        }
        previousTick = now;
    }

    private void invalidate(long now, String reason) {
        ticksSinceRest = -1; observedAt = -1;
        reminder.unavailable(now, reason);
    }

    /** 提醒总入口在死亡、重生、换世界及断线时调用，个人统计不能跨身体复用。 */
    public void clear() {
        ticksSinceRest = -1;
        observedAt = previousTick = lastSleepTick = -1;
        nextRequestAt = 0;
    }
}
