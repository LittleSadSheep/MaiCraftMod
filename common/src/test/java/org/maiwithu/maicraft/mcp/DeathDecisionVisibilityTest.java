// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 死亡后的重生问题与维度无关：承接任务在死亡屏幕上被取消、被新任务替换，或问题随记忆重载整个丢失，
 * MCP 的任务回执与等待都必须继续交出可答的重生问题（实机：下界死亡一秒后任务被取消，问题从此不可见）。
 */
public final class DeathDecisionVisibilityTest {
    public static void main(String[] args) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        IntentRuntime runtime = constructor.newInstance();
        var field = IntentRuntime.class.getDeclaredField("tasks"); field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, IntentTaskRecord> tasks = (Map<UUID, IntentTaskRecord>) field.get(runtime);
        JsonObject context = new JsonObject(); context.addProperty("decision_kind", "death_recovery");

        // 死亡当刻问题挂在正在执行的探索任务上；一秒后它被身体清扫取消，问题仍留在原任务上并默认可见。
        IntentTaskRecord died = running(tasks);
        runtime.requestDeathDecision(died, false, false, context);
        UUID firstId = died.decisionSnapshot().id();
        invoke(died, "terminal", new Class<?>[]{TaskState.class, TaskResult.class, long.class},
                TaskState.CANCELLED, TaskResult.cancelled("semantic task cancelled", "body_gone"), 30L);
        check(runtime.ensureDeathDecision(null, false, false, context) == died,
                "被取消的任务仍挂着问题时，死亡复核沿用它，不另开第二份问题");
        JsonObject status = TaskView.status(died);
        check("cancelled".equals(status.get("state").getAsString()) && status.has("terminal")
                        && firstId.toString().equals(status.getAsJsonObject("decision").get("decision_id").getAsString()),
                "终态任务的默认回执必须同时给出结束结果和仍待答复的重生问题");
        JsonObject woken = AttentionSnapshot.read(runtime, request(runtime, died), true);
        check("decision_required".equals(woken.get("wake_reason").getAsString())
                        && woken.getAsJsonObject("task").has("decision") && !woken.has("death_decision"),
                "盯着原任务的等待必须以待答问题唤醒，而不是只报任务已结束");

        // 死亡屏幕上又提交了新任务：问题随新任务走，旧任务撤下问题，盯着旧任务的等待直接拿到新持有者。
        IntentTaskRecord replacement = running(tasks);
        check(runtime.ensureDeathDecision(replacement, false, false, context) == replacement
                        && replacement.deathDecisionPending() && !died.deathDecisionPending(),
                "替换后的新任务必须接过唯一一份重生问题");
        UUID movedId = replacement.decisionSnapshot().id();
        runtime.ensureDeathDecision(replacement, false, false, context);
        check(movedId.equals(replacement.decisionSnapshot().id()), "问题已在当前任务上时每刻复核不得换发新编号");
        JsonObject pointed = AttentionSnapshot.read(runtime, request(runtime, died), true);
        JsonObject pointer = pointed.getAsJsonObject("death_decision");
        check("decision_required".equals(pointed.get("wake_reason").getAsString())
                        && replacement.externalId().toString().equals(pointer.get("task_id").getAsString())
                        && replacement.externalId().toString().equals(
                                pointer.getAsJsonObject("answer_example").get("task_id").getAsString()),
                "盯着别的任务时也要附上问题和持有者编号，调用者照样板即可答复");
        JsonObject unfiltered = AttentionSnapshot.read(runtime, request(runtime, null), true);
        check(unfiltered.has("death_decision") && "decision_required".equals(unfiltered.get("wake_reason").getAsString()),
                "未指定任务的等待同样直接交出重生问题");

        // 问题随记忆重载整个丢失：死亡复核改挂独立承载记录，重生入口不消失。
        invoke(replacement, "clearPendingDecision", new Class<?>[0]);
        tasks.remove(replacement.externalId());
        IntentTaskRecord host = runtime.ensureDeathDecision(null, false, false, context);
        check(runtime.isDeathRecoveryHost(host) && host.deathDecisionPending()
                        && runtime.deathDecisionHolder() == host,
                "没有任何记录挂着问题时必须挂出独立承载记录");

        // 之后槽位来了新任务：承载记录按"问题已改挂"结算，任务接过问题，仍只有一份。
        IntentTaskRecord later = running(tasks);
        runtime.ensureDeathDecision(later, false, false, context);
        check(host.terminalSnapshot() != null && !host.deathDecisionPending() && later.deathDecisionPending(),
                "承载记录交出问题后结算，迟到答复不会落在旧编号上");

        // 人工点击重生了结死亡：任务单上的死前问题一并撤下，等待不再报待答问题，任务保持暂停等继续。
        runtime.finishSupersededDeathRecovery(60);
        check(runtime.deathDecisionHolder() == null && later.pauseSnapshot() != null && later.resume(),
                "重生后不得残留重生问题，任务仍可明确继续");
        JsonObject alive = AttentionSnapshot.read(runtime, request(runtime, null), true);
        check(!alive.has("death_decision") && !"decision_required".equals(alive.get("wake_reason").getAsString()),
                "活着时等待不得再报死亡问题");
        System.out.println("DeathDecisionVisibilityTest: passed");
    }

    private static IntentTaskRecord running(Map<UUID, IntentTaskRecord> tasks) {
        Goal goal = new Goal("maicraft:travel", "东扇区探索要塞", null, "{}", "{}", List.of(), List.of());
        IntentTaskRecord record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.setState(TaskState.RUNNING);
        tasks.put(record.externalId(), record);
        return record;
    }

    private static JsonObject request(IntentRuntime runtime, IntentTaskRecord task) {
        return PublicToolCatalog.validateAndNormalize("perceive", AttentionSnapshot.continuation(
                runtime.attentionCheckpoint(), task == null ? null : task.externalId().toString()));
    }

    private static void invoke(IntentTaskRecord task, String name, Class<?>[] signature, Object... args) throws Exception {
        var method = IntentTaskRecord.class.getDeclaredMethod(name, signature);
        method.setAccessible(true); method.invoke(task, args);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
