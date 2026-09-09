package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session-local timers measured against the active client world's game clock.
 *
 * <p>They deliberately do not survive a body/world replacement. Due reminders
 * are shown locally and removed; no packet, saved data, or event bus is involved.</p>
 */
public final class TimerRegistry {

    public static final int MAX_PER_COMPANION = 8;
    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 1200;

    private static final TimerRegistry INSTANCE = new TimerRegistry();
    private static final long SWEEP_INTERVAL_TICKS = 20L;

    public record Timer(String id, UUID companion, long dueGameTime, String reason) {}

    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private long nextId = 1L;
    private long nextSweepGameTime = Long.MIN_VALUE;

    private TimerRegistry() {}

    public static TimerRegistry get() {
        return INSTANCE;
    }

    public static int clampSeconds(int requested) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, requested));
    }

    public Timer set(UUID playerUuid, long nowGameTime, int seconds, String reason) {
        // 只记“什么时候提醒”，不用走路或停下手头工作；SetTimerTool 已把秒数限制在允许范围内。
        if (list(playerUuid).size() >= MAX_PER_COMPANION) {
            return null;
        }
        Timer timer = new Timer("tm" + nextId++, playerUuid,
                nowGameTime + seconds * 20L, reason);
        timers.put(timer.id(), timer);
        return timer;
    }

    public List<Timer> list(UUID playerUuid) {
        return timers.values().stream()
                .filter(timer -> timer.companion().equals(playerUuid))
                .sorted(Comparator.comparingLong(Timer::dueGameTime))
                .toList();
    }

    public boolean cancel(UUID playerUuid, String id) {
        Timer timer = timers.get(id);
        if (timer == null || !timer.companion().equals(playerUuid)) {
            return false;
        }
        timers.remove(id);
        return true;
    }

    public static long remainingSeconds(Timer timer, long nowGameTime) {
        // 不足一秒仍显示一秒；到期后归零，不显示负数。
        return (Math.max(0L, timer.dueGameTime() - nowGameTime) + 19L) / 20L;
    }

    public static void tick(LocalPlayer player) {
        // 每过二十个游戏刻查一次（正常速度约一秒）；没有调用这里时，即使已经到点也不会提醒。
        long now = player.level().getGameTime();
        TimerRegistry registry = INSTANCE;
        if (registry.nextSweepGameTime != Long.MIN_VALUE && now < registry.nextSweepGameTime) {
            return;
        }
        registry.nextSweepGameTime = now + SWEEP_INTERVAL_TICKS;
        registry.fireDue(player, now);
    }

    static void clear() {
        INSTANCE.timers.clear();
        INSTANCE.nextId = 1L;
        INSTANCE.nextSweepGameTime = Long.MIN_VALUE;
    }

    List<Timer> dueAt(long nowGameTime) {
        return timers.values().stream()
                .filter(timer -> timer.dueGameTime() <= nowGameTime)
                .sorted(Comparator.comparingLong(Timer::dueGameTime))
                .toList();
    }

    private void fireDue(LocalPlayer player, long nowGameTime) {
        // 先找出全部到期提醒，再逐个显示并删掉；这里只让自己看到消息，不向服务器发聊天。
        List<Timer> due = new ArrayList<>();
        for (Timer timer : dueAt(nowGameTime)) {
            if (timer.companion().equals(player.getUUID())) {
                due.add(timer);
            }
        }
        for (Timer timer : due) {
            player.displayClientMessage(Component.literal(
                    "⏱ Reminder " + timer.id() + ": " + timer.reason()), false);
            timers.remove(timer.id());
        }
    }
}
