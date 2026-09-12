// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

public final class MachineWatchTaskRecord extends TaskRecord {
    static { TaskFactory.register(MachineWatchTaskRecord.class,MachineWatchTask::new); }
    public final ProductionRunPlan plan;
    public final String label;
    public final int minimumProcessEvents, durationTicks, idleTicks;
    public MachineWatchTaskRecord(String callId, long deadline, String label, ProductionRunPlan plan,
                                  int minimumProcessEvents, int durationTicks, int idleTicks) {
        super("register_machine_watch",callId,deadline);
        this.label = label; this.plan = plan;
        if (minimumProcessEvents < 1 || minimumProcessEvents > 100 || durationTicks < 20 || durationTicks > 72000
                || idleTicks < 20 || idleTicks > durationTicks) throw new IllegalArgumentException("Invalid passive production watch limits");
        this.minimumProcessEvents = minimumProcessEvents; this.durationTicks = durationTicks; this.idleTicks = idleTicks;
    }
    @Override public String describe() { return "登记后台生产观察 · " + label; }
}
