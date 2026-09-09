// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import org.maiwithu.maicraft.task.TaskRecord;

// 关闭已登记机器菜单的任务单；没有独立业务状态，真正要关哪个菜单由执行器读取当前会话。
public final class MachineMenuCloseTaskRecord extends TaskRecord {
    public MachineMenuCloseTaskRecord(String callId, long deadline) { super("machine_close_menu", callId, deadline); }
    @Override public String describe() { return "close the previously opened machine menu"; }
}
