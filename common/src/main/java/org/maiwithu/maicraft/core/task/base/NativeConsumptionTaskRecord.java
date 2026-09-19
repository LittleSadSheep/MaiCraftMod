// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.util.function.BooleanSupplier;
import org.maiwithu.maicraft.task.TaskRecord;

/** 原生加工在消费材料或经验前等待同一持久屏障；包装任务必须传递它，不能用固定成功绕过崩溃保护。 */
public abstract class NativeConsumptionTaskRecord extends TaskRecord {
    private final String namespace;
    private BooleanSupplier submissionBarrier;
    private boolean consumptionReserved;

    protected NativeConsumptionTaskRecord(String toolName, String callId, long deadline, String namespace) {
        super(toolName, callId, deadline);
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalid native consumption namespace");
        this.namespace = namespace;
    }

    public final void submissionBarrier(BooleanSupplier barrier) {
        // 一张任务单只能绑定一次来源明确的屏障，避免换包装后用另一次预约替代已经消费的操作。
        if (submissionBarrier != null || barrier == null) throw new IllegalStateException("native consumption barrier must be bound exactly once");
        submissionBarrier = barrier;
    }

    public final boolean prepareNativeConsumptionBoundary() {
        if (submissionBarrier == null) throw new IllegalStateException("native consumption requires a durable submission barrier");
        // 开始保存就关闭普通重试；真正允许投料或附魔仍要等父检查点和消费预约都确认落盘。
        consumptionReserved = true;
        return submissionBarrier.getAsBoolean();
    }

    public final boolean nativeConsumptionReserved() { return consumptionReserved; }
    public final String consumptionNamespace() { return namespace; }
}
