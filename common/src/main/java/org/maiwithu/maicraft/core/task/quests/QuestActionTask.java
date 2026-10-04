// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.quests;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestActionSession;
import org.maiwithu.maicraft.core.integration.ftbquests.MinecraftFtbQuestActions;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 角色接管期间发出原生任务书动作；被临时自救抢占时保留会话，取消后仍结算已发生的效果。 */
public final class QuestActionTask implements Task {
    private final LocalPlayer owner;
    private final FtbQuestActionSession session;
    public QuestActionTask(LocalPlayer player, QuestActionTaskRecord record) {
        owner = player; session = new FtbQuestActionSession(record.request, new MinecraftFtbQuestActions(player),
                record::prepareSubmission, () -> System.nanoTime() / 1_000_000);
    }
    @Override public TaskState tick(LocalPlayer player) {
        if (player != owner) { session.cancel("角色发生变化，停止继续提交并保留已有观察"); return TaskState.CANCELLED; }
        try {
            var context = ClientRuntime.requireContext(player);
            context.body().releaseAll();
            // 本刻已有原生动作时等下一刻；发送前另行复查身体控制权，尝试发送后只推进观察，不再消耗第二次。
            if (!session.submitted() && !context.mutationAvailable()) return TaskState.RUNNING;
            return switch (session.tick()) {
                case RUNNING -> TaskState.RUNNING;
                case SUCCESS -> TaskState.SUCCESS;
                case FAILED -> TaskState.FAILED;
                case CANCELLED -> TaskState.CANCELLED;
            };
        } catch (RuntimeException unavailable) {
            session.fail("身体上下文不可用：" + unavailable.getMessage()); return TaskState.FAILED;
        }
    }
    @Override public void stop(LocalPlayer player, StopReason reason) {
        // 临时抢占保留同一个会话和发送记录；单调时钟继续走，恢复时不会重新取得一段完整观察窗口。
        if (reason != StopReason.PREEMPTED) session.cancel("任务书操作被取消；已经发出的原生请求无法撤回");
    }
    @Override public TaskResult result(TaskState terminal) {
        // 收尾沿用已记录的结果；已结束会话不会被这里改写成新的取消，也不会撤回服务器可能已经处理的请求。
        if (terminal == TaskState.FAILED) session.fail("任务执行结束，保留已提交状态和真实观察");
        else if (terminal != TaskState.SUCCESS) session.cancel("任务结束，保留已提交状态和真实观察");
        return session.result();
    }
    @Override public String name() { return "quest_action"; }
    @Override public boolean keepsGuiOnCompletion() { return true; }
    @Override public Map<String, Object> progress() { return session.evidence(); }
}
