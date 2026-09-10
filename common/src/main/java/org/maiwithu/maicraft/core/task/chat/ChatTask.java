// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chat;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.ChatSession;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Map;

/**
 * 把一次聊天接入任务调度。输入时松开移动按键；暂时被自救打断就保留进度，其他结束原因则取消。
 * 只有确认已交给原版客户端才返回成功，服务器收信与命令结果由会话说明另行标注。
 */
public final class ChatTask implements Task {
    private final LocalPlayer player;
    private final ChatSession session;

    public ChatTask(LocalPlayer player, ChatTaskRecord record) {
        this.player = player;
        session = new ChatSession(record.message);
    }

    @Override public TaskState tick(LocalPlayer companion) {
        if (companion != player) {
            session.cancel("The local player changed before chat submission.");
            return TaskState.CANCELLED;
        }
        try {
            var context = ClientRuntime.requireContext(player);
            context.body().releaseAll();
            return switch (session.tick(context, System.nanoTime())) {
                case TYPING -> TaskState.RUNNING;
                case SUBMITTED -> TaskState.SUCCESS;
                case CANCELLED -> TaskState.CANCELLED;
                case FAILED, UNCERTAIN -> TaskState.FAILED;
            };
        } catch (RuntimeException failure) {
            // cancel preserves an uncertain native submission instead of enabling a resend.
            session.cancel("Chat stopped before submission: " + failure.getClass().getSimpleName());
            return TaskState.FAILED;
        }
    }

    @Override public void stop(LocalPlayer companion, StopReason reason) {
        if (reason == StopReason.PREEMPTED) session.suspend();
        else session.cancel("The chat task was cancelled before submission.");
    }

    @Override public TaskResult result(TaskState terminal) {
        session.cancel("The chat task ended before submission.");
        boolean success = terminal == TaskState.SUCCESS && session.status() == ChatSession.Status.SUBMITTED;
        return new TaskResult(success, session.detail(), terminal == TaskState.TIMEOUT,
                terminal == TaskState.CANCELLED, session.evidence());
    }

    @Override public String name() { return "chat"; }
    @Override public Map<String, Object> progress() { return session.evidence(); }
}
