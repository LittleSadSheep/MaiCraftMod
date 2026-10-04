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
            // 等待身体的真实修改权限；发包后只读回执，即使不能再操作身体也要继续整理已发生的事实。
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
        if (reason != StopReason.PREEMPTED) session.cancel("任务书操作被取消；已经发出的原生请求无法撤回");
    }
    @Override public TaskResult result(TaskState terminal) {
        if (terminal == TaskState.FAILED) session.fail("任务执行结束，保留已提交状态和真实观察");
        else if (terminal != TaskState.SUCCESS) session.cancel("任务结束，保留已提交状态和真实观察");
        return session.result();
    }
    @Override public String name() { return "quest_action"; }
    @Override public boolean keepsGuiOnCompletion() { return true; }
    @Override public Map<String, Object> progress() { return session.evidence(); }

    /** 面板行动行的一句话汇报；阶段来自任务书会话是否已发出原生请求。 */
    @Override
    public String describeCurrentAction() {
        return session.submitted() ? "正在等待任务书操作结果" : "正在提交任务书操作";
    }
}
