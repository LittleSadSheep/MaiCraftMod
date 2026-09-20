// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

/** 一炉的实物账，只接收已经确认的装料、退料和收货；等待中的点击不入账。 */
final class CookingBatchLedger {
    private int loaded;
    private int returned;
    private int collected;

    void begin(int inputCount) {
        loaded = inputCount;
        returned = 0;
        collected = 0;
    }

    void reset() { begin(0); }
    int loaded() { return loaded; }
    int returned() { return returned; }
    int collected() { return collected; }
    void recordReturn(int count) { returned += count; }
    void recordCollection(int count) { collected += count; }

    int outstandingInput() { return loaded - returned; }

    long expectedOutput(int inputInMachine, int outputPerInput) {
        // 已退回背包的原料没有被烧掉，必须先扣掉，再把真正消耗的原料换算成产物。
        return (long) (outstandingInput() - inputInMachine) * outputPerInput;
    }

    long observedOutput(int outputInMachine) {
        return (long) collected + outputInMachine;
    }
}
