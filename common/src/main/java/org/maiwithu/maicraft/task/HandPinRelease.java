package org.maiwithu.maicraft.task;

/**
 * 判断什么时候可以解除“手里一直拿着这个物品”的要求。
 * 例如刚装备好镐子，下一条挖矿指令还没到，不能因为短暂没任务就马上解除要求。
 * 因此要连续空闲一段时间才提醒调用方解除；主动取消和死亡时则由其他代码立即解除。
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
