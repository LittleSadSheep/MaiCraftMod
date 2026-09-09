// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Objects;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存把已观察拉杆设为开或关的请求；位置来自机器观察或地标，真正点击和验证由 MachineControlTask 执行。
 */
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
