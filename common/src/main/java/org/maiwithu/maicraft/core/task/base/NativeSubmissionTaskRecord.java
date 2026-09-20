// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.util.function.BooleanSupplier;
import org.maiwithu.maicraft.task.TaskRecord;

/** 不可自动重复的原生操作提交前等待持久屏障；包装任务必须传递同一屏障，不能绕过重启后的重复检查。 */
public abstract class NativeSubmissionTaskRecord extends TaskRecord {
    private final String namespace;
    private BooleanSupplier submissionBarrier;
    private boolean submissionReserved;

    protected NativeSubmissionTaskRecord(String toolName, String callId, long deadline, String namespace) {
        super(toolName, callId, deadline);
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalid native submission namespace");
        this.namespace = namespace;
    }

    public final void submissionBarrier(BooleanSupplier barrier) {
        // 一张任务单只能绑定一次来源明确的屏障，避免换包装后把已提交的操作变成另一份预约。
        if (submissionBarrier != null || barrier == null) throw new IllegalStateException("native submission barrier must be bound exactly once");
        submissionBarrier = barrier;
    }

    public final boolean prepareSubmission() {
        if (submissionBarrier == null) throw new IllegalStateException("native submission requires a durable submission barrier");
        // 开始保存就记下已进入不可自动重试的边界；真正提交仍要等父检查点和操作预约都确认落盘。
        submissionReserved = true;
        return submissionBarrier.getAsBoolean();
    }

    public final boolean submissionReserved() { return submissionReserved; }
    public final String submissionNamespace() { return namespace; }
}
