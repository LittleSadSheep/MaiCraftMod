// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

/**
 * 重试退避：同一个办法再试之前等多少刻。
 *
 * <p>失败原因需要时间自己消失（例如时间窗没到、服务器瞬忙）时，立刻重试只会把同一种失败再撞一遍；
 * 等待按失败次数逐次加倍，到上限为止，避免无限拉长。
 *
 * <p>纯函数：只按失败次数算出刻数，不碰时钟，也不真的等待；等 由调用方按刻数执行。
 */
public final class Backoff {

    private final long baseTicks;
    private final long maxTicks;

    /**
     * @param baseTicks 第一次重试前等多少刻
     * @param maxTicks  等待的上限刻数；失败次数再多也不超过它
     */
    public Backoff(long baseTicks, long maxTicks) {
        if (baseTicks <= 0 || maxTicks < baseTicks) {
            throw new IllegalArgumentException("退避基数必须为正，且上限不得小于基数");
        }
        this.baseTicks = baseTicks;
        this.maxTicks = maxTicks;
    }

    /**
     * 同一种失败已经发生 failureCount 次（从 1 数起）后，下一次重试前等多少刻：
     * 基数按次数逐次加倍，封顶为构造时给的上限。
     */
    public long waitTicks(int failureCount) {
        if (failureCount <= 1) return baseTicks;
        long wait = baseTicks;
        for (int i = 1; i < failureCount; i++) {
            wait <<= 1;
            if (wait >= maxTicks) return maxTicks;
        }
        return wait;
    }
}
