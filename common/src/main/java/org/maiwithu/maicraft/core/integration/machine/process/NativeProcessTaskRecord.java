// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 建造后仍使用同一固定锚点加工；包装与原生子任务共享消费命名空间和持久屏障。 */
public final class NativeProcessTaskRecord extends NativeSubmissionTaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(NativeProcessTaskRecord.class, NativeProcessTask::new); }
    public final NativeProcessRequest request;
    public final BlockPos anchor;
    public final String dimension;
    public final TaskRecord construction;
    private Position verified;

    public NativeProcessTaskRecord(String callId, long deadline, NativeProcessRequest request,
                                   BlockPos anchor, String dimension, TaskRecord construction) {
        super("run_native_process", callId, deadline, NativeProcessRegistry.consumptionNamespace(request.process()));
        this.request = Objects.requireNonNull(request); this.anchor = Objects.requireNonNull(anchor).immutable();
        this.dimension = Objects.requireNonNull(dimension); this.construction = construction;
    }
    void verified() { verified = new Position(anchor.getX(), anchor.getY(), anchor.getZ(), dimension); }
    @Override public Position internalVerifiedPosition() { return verified; }
    @Override public String describe() { return "原生加工 · " + request.process(); }
}
