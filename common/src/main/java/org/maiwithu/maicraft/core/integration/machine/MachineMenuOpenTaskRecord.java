// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Objects;
import org.maiwithu.maicraft.task.TaskRecord;

// 保存“打开哪台已观察机器”的请求与任务期限，实际接近、打开和核对交给 MachineMenuOpenTask。
public final class MachineMenuOpenTaskRecord extends TaskRecord {
    public final MachineMenu.OpenRequest request;
    public MachineMenuOpenTaskRecord(String callId, long deadline, MachineMenu.OpenRequest request) {
        super("machine_open_menu", callId, deadline);
        this.request = Objects.requireNonNull(request, "request");
    }
    @Override public String describe() { return "open and inspect one observed machine menu"; }
}
