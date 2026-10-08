// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.progress;

/**
 * 进度表：只要在真实前进就继续，原地打转超过窗口才算卡住，另有总时长上限。
 *
 * <p>按"执行器实际被推进的刻数"计时：被反射抢占、被人工暂停的时间本来就不会推进执行器，
 * 因此天然不计入，不需要像 v1 那样在 139 处手动延长截止时间。
 *
 * <p>什么算真实进展由调用方报告：距离缩短、方块确认放下、物品入包、原生效果确认……
 * "动作已发出"不算进展。
 */
public final class ProgressMeter {
    private final long stallWindowTicks;
    private final long hardCapTicks;
    private long activeTicks;
    private long lastProgressTick;
    private String lastSignal = "开始";

    /**
     * @param stallWindowTicks 多少个推进刻没有真实进展就算卡住
     * @param hardCapTicks     总共最多推进多少刻；不需要上限时传 {@link Long#MAX_VALUE}
     */
    public ProgressMeter(long stallWindowTicks, long hardCapTicks) {
        if (stallWindowTicks <= 0 || hardCapTicks <= 0) {
            throw new IllegalArgumentException("停滞窗口与总上限都必须为正");
        }
        this.stallWindowTicks = stallWindowTicks;
        this.hardCapTicks = hardCapTicks;
    }

    /** 执行器每被推进一刻就调用一次。 */
    public void tick() {
        activeTicks++;
    }

    /** 报告一次真实进展，并写明是什么进展，卡住时回执会引用最后一次进展。 */
    public void advanced(String signal) {
        lastProgressTick = activeTicks;
        lastSignal = signal;
    }

    /** 当前判定。 */
    public Verdict verdict() {
        if (activeTicks >= hardCapTicks) return new Verdict.OverCap(activeTicks);
        long idle = activeTicks - lastProgressTick;
        if (idle >= stallWindowTicks) return new Verdict.Stalled(lastSignal, idle);
        return Verdict.MOVING;
    }

    /** 已推进的刻数。 */
    public long activeTicks() {
        return activeTicks;
    }

    /** 进度判定。 */
    public sealed interface Verdict {
        /** 还在前进，或停下的时间还没超过窗口。 */
        Verdict MOVING = new Moving();

        /** 还在前进。 */
        record Moving() implements Verdict {}

        /** 原地打转：自最后一次进展（lastSignal）起已推进 idleTicks 刻没有新进展。 */
        record Stalled(String lastSignal, long idleTicks) implements Verdict {}

        /** 超过总时长上限。 */
        record OverCap(long activeTicks) implements Verdict {}
    }
}
