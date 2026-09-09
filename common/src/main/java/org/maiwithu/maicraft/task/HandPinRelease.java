package org.maiwithu.maicraft.task;

/**
 * 连续空闲够久时返回一次 true，供调用方发出旧的工作结束通知。它本身不会解除手持物品要求。
 * 当前通知列表没有监听者，因此这段计时仍在运行，但没有对应的手持解除效果。
 */
public final class HandPinRelease {

    private final int graceTicks;
    private int idleTicks;
    private boolean fired;

    public HandPinRelease(int graceTicks) {
        this.graceTicks = graceTicks;
    }

    /** 每次传入“还有没有任务”；连续空闲够久时只返回一次 true，收到新任务后重新计数。 */
    public boolean tick(boolean llmBusy) {
        if (llmBusy) {
            // 又有活干了，之前等了多久作废，从下一次空闲重新计算。
            idleTicks = 0;
            fired = false;
            return false;
        }
        if (fired) return false;
        // 达到等待长度后只提醒一次，避免每一刻都重复解除手持要求。
        if (++idleTicks >= graceTicks) {
            fired = true;
            return true;
        }
        return false;
    }
}
