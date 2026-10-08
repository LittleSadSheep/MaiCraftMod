// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/** 预算只表示连续无进展时长；确认新进展后补满，普通轮询不会补时。调用者统一提供活动时间。 */
public final class ProgressBudget {
    private final long allowance;
    private long lastProgress = Long.MIN_VALUE;
    private long lastSample = Long.MIN_VALUE;
    private long counterHighWater;
    private final LongUnaryOperator activeClock;
    private final LongSupplier inheritedProgress;
    private final Runnable publishProgress;
    private final LongConsumer renewDeadline;
    private long inheritedRevision;

    public ProgressBudget(long allowance) {
        this(allowance, time -> time, () -> 0, () -> {}, deadline -> {});
    }

    /** 未挂在自己任务下的施工、交通辅助流程绑定当前任务作用域，向父任务交付真实进展并继承暂停。 */
    public static ProgressBudget currentTask(long allowance) {
        TaskDeadlineClock clock = TaskDeadlineClock.active();
        if (clock == null) return new ProgressBudget(allowance);
        long baseline = clock.pausedTicks;
        return new ProgressBudget(allowance, time -> time - (clock.pausedTicks - baseline),
                () -> clock.progressRevision, () -> clock.progressRevision++, deadline -> {});
    }

    ProgressBudget(long allowance, LongUnaryOperator activeClock, LongSupplier inheritedProgress,
                   Runnable publishProgress, LongConsumer renewDeadline) {
        if (allowance <= 0) throw new IllegalArgumentException("positive progress allowance required");
        this.allowance = allowance;
        this.activeClock = activeClock; this.inheritedProgress = inheritedProgress;
        this.publishProgress = publishProgress; this.renewDeadline = renewDeadline;
        inheritedRevision = inheritedProgress.getAsLong();
    }

    public boolean observe(long time, boolean progressed) {
        long now = activeClock.applyAsLong(time);
        // 子任务的真实进展能给父流程补时；接收进展只续期，不再反向发布，避免父子相互空转续命。
        boolean inherited = inheritedProgress.getAsLong() != inheritedRevision;
        if (progressed) publishProgress.run();
        inheritedRevision = inheritedProgress.getAsLong();
        progressed |= inherited;
        if (lastProgress == Long.MIN_VALUE || now < lastSample || progressed) lastProgress = now;
        lastSample = now;
        if (progressed) renewDeadline.accept(time > Long.MAX_VALUE - allowance ? Long.MAX_VALUE : time + allowance);
        return idleActive(now) >= allowance;
    }

    /** 单调累计的已确认工作量可直接接入；相同值、重试归零和重复读取都不补时。 */
    public boolean observeCounter(long now, long completed) {
        boolean changed = completed > counterHighWater;
        if (changed) counterHighWater = completed;
        return observe(now, changed);
    }

    private long idleActive(long now) { return lastProgress == Long.MIN_VALUE ? 0 : Math.max(0, now - lastProgress); }
    public long idle(long now) { return idleActive(activeClock.applyAsLong(now)); }
    public long remaining(long now) { return Math.max(0, allowance - idle(now)); }
    public Map<String, Object> diagnostics(long now) {
        return Map.of("allowance", allowance, "remaining", remaining(now), "idle", idle(now), "started", lastProgress != Long.MIN_VALUE);
    }
}
