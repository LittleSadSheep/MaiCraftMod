// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** body_gone 抢先终结任务后，死亡恢复决策仍然必须可回答：它是重生入口的唯一 MCP 通道。 */
public final class DeathDecisionSurvivesCancelTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var record = new IntentTaskRecord(UUID.randomUUID(), null, travel());
        var death = deathDecision();
        record.requestDecision(death, 100);
        // 复现 body_gone 竞态：决策挂起期间任务被终结，终态快照会清空挂起决策。
        record.terminal(TaskState.CANCELLED, TaskResult.fail("body_gone", java.util.Map.of()), 150);
        check(record.answer(death.id(), "respawn", new JsonObject()),
                "被 body_gone 终结后死亡恢复决策仍必须可回答，否则重生没有 MCP 入口");
        Object taken = record.takeAnswer();
        check(taken != null, "答复进入待取队列供调用方同步应用");

        // 对照：普通决策随任务终态一并作废，迟到的答复依旧拒绝。
        var record2 = new IntentTaskRecord(UUID.randomUUID(), null, travel());
        var plain = new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(), "普通问题",
                List.of(new IntentTaskRecord.DecisionOption("retry", "重试")),
                "{}");
        record2.requestDecision(plain, 100);
        record2.terminal(TaskState.CANCELLED, TaskResult.fail("body_gone", java.util.Map.of()), 150);
        check(!record2.answer(plain.id(), "retry", new JsonObject()),
                "普通决策随任务终态作废，迟到答复依旧拒绝");

        // 检查点重载：死亡问题属于存盘时那条命，不随记录恢复（仍在死亡屏幕时由死亡复核重挂）；普通问题照旧恢复。
        var reloadedDeath = restored(deathDecision());
        check(reloadedDeath.decisionSnapshot() == null && reloadedDeath.pauseSnapshot() != null
                        && reloadedDeath.resume(),
                "重载后的死亡问题必须撤下，任务保持暂停且可以继续，活着时不能再被重生问题挡住");
        var reloadedPlain = restored(plain);
        check(reloadedPlain.decisionSnapshot() != null && reloadedPlain.decisionSnapshot().id().equals(plain.id()),
                "普通待答问题仍随检查点恢复");
        System.out.println("DeathDecisionSurvivesCancelTest: passed");
    }

    private static IntentTaskRecord restored(IntentTaskRecord.DecisionSnapshot decision) {
        Goal goal = travel();
        return IntentTaskRecord.restored(UUID.randomUUID(), null, goal, "death-test", List.of(goal), 0,
                List.of(), java.util.Map.of(), java.util.Map.of(), List.of(), decision, null, null, 200);
    }

    private static IntentTaskRecord.DecisionSnapshot deathDecision() {
        return new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(),
                "The agent died without auto-respawn authorisation. Respawn or cancel the task?",
                List.of(new IntentTaskRecord.DecisionOption("respawn", "Request native respawn."),
                        new IntentTaskRecord.DecisionOption("cancel_task", "Cancel the task.")),
                "{\"decision_kind\":\"death_recovery\",\"hardcore\":false}");
    }

    private static Goal travel() {
        return new Goal("maicraft:travel", "回到地表", null, "{}", "{}", List.of(), List.of());
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
