// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;

/** Optional native observations enrich an already-created structural snapshot under the same identity. */
public final class ServerMachineObservationTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(ServerMachineObservationTaskRecord.class, ServerMachineObservationTask::new); }
    final MachineSnapshots.Snapshot snapshot;
    final int componentOffset;
    final int resourceOffset;
    private Position verified;

    public ServerMachineObservationTaskRecord(String callId, long deadline, MachineSnapshots.Snapshot snapshot) {
        this(callId, deadline, snapshot, 0);
    }

    public ServerMachineObservationTaskRecord(String callId, long deadline, MachineSnapshots.Snapshot snapshot, int componentOffset) {
        this(callId, deadline, snapshot, componentOffset, 0);
    }

    public ServerMachineObservationTaskRecord(String callId, long deadline, MachineSnapshots.Snapshot snapshot,
                                              int componentOffset, int resourceOffset) {
        super("machine_server_observation", callId, deadline);
        this.snapshot = java.util.Objects.requireNonNull(snapshot);
        if (componentOffset < 0 || componentOffset > 768) throw new IllegalArgumentException("component offset must be 0..768");
        this.componentOffset = componentOffset;
        if (resourceOffset < 0 || resourceOffset > 4096) throw new IllegalArgumentException("resource offset must be 0..4096");
        this.resourceOffset = resourceOffset;
    }

    @Override public String describe() { return "observe native machine state for " + snapshot.label(); }
    void observed() {
        var center = snapshot.center();
        verified = new Position(center.getX(), center.getY(), center.getZ(), snapshot.dimension());
    }
    @Override public Position internalVerifiedPosition() { return verified; }
}
