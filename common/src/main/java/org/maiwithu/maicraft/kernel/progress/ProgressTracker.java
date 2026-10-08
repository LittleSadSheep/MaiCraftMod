// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.progress;

/**
 * 进度跟踪：只要在真实前进就继续，原地打转超过一段时间才算卡住，另有最多能做多久的上限。
 *
 * <p>按"任务实际被推进的刻数"计时：被生存需求打断、被手动暂停的时间本来就不会推进任务，
 * 因此天然不计入，不需要在打断前后手动延长截止时间。
 *
 * <p>什么算真实进展由调用方报告：距离缩短、方块确认放下、物品进了背包、游戏确认了交互……
 * "交互已提交"不算进展。
 */
public final class ProgressTracker {
    private final long stuckAfterTicks;
    private final long maxTicks;
    private long activeTicks;
    private long lastProgressTick;
    private String lastProgress = "开始";

    /**
     * @param stuckAfterTicks 连续多少个推进刻没有真实进展就算卡住
     * @param maxTicks        最多推进多少刻；不需要上限时传 {@link Long#MAX_VALUE}
     */
    public ProgressTracker(long stuckAfterTicks, long maxTicks) {
        if (stuckAfterTicks <= 0 || maxTicks <= 0) {
            throw new IllegalArgumentException("卡住判定的刻数与最多推进的刻数都必须为正");
        }
        this.stuckAfterTicks = stuckAfterTicks;
        this.maxTicks = maxTicks;
    }

    /** 任务每被推进一刻就调用一次。 */
    public void tick() {
        activeTicks++;
    }

    /** 记一次真实进展，并写明是什么进展；卡住时结果会引用最后一次进展。 */
    public void recordProgress(String what) {
        lastProgressTick = activeTicks;
        lastProgress = what;
    }

    /** 现在的情况。 */
    public Status status() {
        if (activeTicks >= maxTicks) return new Status.TimedOut(activeTicks);
        long idle = activeTicks - lastProgressTick;
        if (idle >= stuckAfterTicks) return new Status.Stuck(lastProgress, idle);
        return Status.PROGRESSING;
    }

    /** 已推进的刻数。 */
    public long activeTicks() {
        return activeTicks;
    }

    /** 进度情况。 */
    public sealed interface Status {
        /** 还在前进，或者停下的时间还没长到算卡住。 */
        Status PROGRESSING = new Progressing();

        /** 还在前进。 */
        record Progressing() implements Status {}

        /** 卡住了：自最后一次进展（lastProgress）起，已推进 idleTicks 刻没有新进展。 */
        record Stuck(String lastProgress, long idleTicks) implements Status {}

        /** 超过了最多能做的时长。 */
        record TimedOut(long activeTicks) implements Status {}
    }
}
