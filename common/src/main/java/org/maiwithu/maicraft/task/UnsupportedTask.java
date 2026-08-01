package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

/** Fails an unregistered record cleanly instead of hanging its tool call. */
@org.maiwithu.maicraft.api.Internal
public final class UnsupportedTask implements Task {

    private final String toolName;

    public UnsupportedTask(TaskRecord record) {
        this.toolName = record.getToolName();
    }

    @Override
    public TaskState tick(LocalPlayer player) {
        return TaskState.FAILED;
    }

    @Override
    public TaskResult result(TaskState finalState) {
        return TaskResult.fail("no local-player executor is registered for " + toolName);
    }

    @Override
    public void stop(LocalPlayer player, StopReason why) {
    }

    @Override
    public String name() {
        return "unsupported";
    }
}
