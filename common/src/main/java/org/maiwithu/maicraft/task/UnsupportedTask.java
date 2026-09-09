package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

/** 没找到负责执行这类任务的代码时，返回这个对象，让任务明确失败，而不是一直等不到结果。 */
@org.maiwithu.maicraft.api.Internal
public final class UnsupportedTask implements Task {

    private final String toolName;

    public UnsupportedTask(TaskRecord record) {
        this.toolName = record.getToolName();
    }

    @Override
    public TaskState tick(LocalPlayer player) {
        // 没有可执行的动作，第一次被调度就结束并报告未注册的工具名。
        return TaskState.FAILED;
    }

    @Override
    public TaskResult result(TaskState finalState) {
        return TaskResult.fail("no local-player executor is registered for " + toolName);
    }

    @Override
    public void stop(LocalPlayer player, StopReason why) {
        // 从未操作玩家，没有需要停止的按键、导航或菜单。
    }

    @Override
    public String name() {
        return "unsupported";
    }
}
