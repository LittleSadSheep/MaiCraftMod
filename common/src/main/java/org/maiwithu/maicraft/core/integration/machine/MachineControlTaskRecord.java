// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Objects;
import org.maiwithu.maicraft.task.TaskRecord;

/** Internal typed action; positions originate in the surveyed machine and named landmarks. */
public final class MachineControlTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "machine_control";
    public final MachineControl.Request request;

    public MachineControlTaskRecord(
            String callId, long deadlineGameTime, MachineControl.Request request) {
        super(TOOL_NAME, callId, deadlineGameTime);
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override public String describe() {
        return "set existing machine lever " + (request.desiredPowered() ? "powered" : "unpowered");
    }
}
