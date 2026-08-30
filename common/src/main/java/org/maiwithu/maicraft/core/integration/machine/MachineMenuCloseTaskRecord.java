// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import org.maiwithu.maicraft.task.TaskRecord;

public final class MachineMenuCloseTaskRecord extends TaskRecord {
    public MachineMenuCloseTaskRecord(String callId, long deadline) { super("machine_close_menu", callId, deadline); }
    @Override public String describe() { return "close the previously opened machine menu"; }
}
