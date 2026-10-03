// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 本次寻死只绑定当前身体；重生前由父任务记录已完成步骤，避免恢复后再次寻死。 */
public final class SuicideTaskRecord extends TaskRecord {
    static { TaskFactory.register(SuicideTaskRecord.class, SuicideTask::new); }
    final SuicideRequest request;

    public SuicideTaskRecord(String callId, SuicideRequest request) {
        super("suicide", callId, NO_DEADLINE);
        this.request = request;
    }
}
