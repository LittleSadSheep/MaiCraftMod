// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 死亡瞬间没有任务承接时，恢复决策挂在运行时的全局承载记录上：
 * task list/get/answer 原样可达，答复与人工重生各有收尾路径，承载记录不进检查点。
 */
public final class StandaloneDeathRecoveryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        IntentRuntime runtime = IntentRuntime.get();

        // 无任务死亡：恢复态存在、经 task/tasks 可达、决策可读，且 isDeathRecoveryHost 识别它。
        IntentTaskRecord host = runtime.openDeathRecoveryDecision(false, false, new JsonObject());
        IntentTaskRecord.DecisionSnapshot decision = host.decisionSnapshot();
        check(decision != null, "无任务宿主死亡必须挂出 death_recovery 决策");
        check("death_recovery".equals(decision.context().get("decision_kind").getAsString()),
                "全局恢复态决策必须标记 decision_kind=death_recovery");
        check(runtime.task(host.externalId()) == host, "task get 必须能按编号找回全局恢复态");
        check(runtime.tasks(8).contains(host), "task list 必须展示全局恢复态");
        check(runtime.isDeathRecoveryHost(host), "承载记录必须被 isDeathRecoveryHost 识别");
        check(!host.allStepsSucceeded(), "零步骤承载记录不得虚报 all_steps_succeeded");

        // 答复 respawn 后收尾：记录终态化但仍可查阅，不能凭空消失。
        UUID decisionId = decision.id();
        check(host.answer(decisionId, "respawn", new JsonObject()), "全局恢复态的 respawn 答复必须被接受");
        runtime.finishDeathRecovery(host, TaskState.SUCCESS,
                new TaskResult(true, "Native respawn was requested.", false, false, Map.of()), 10);
        check(host.terminalSnapshot() != null && host.terminalSnapshot().state() == TaskState.SUCCESS,
                "已答复的承载记录必须随答复终态化");
        check(runtime.task(host.externalId()) == host, "结算后的承载记录仍须经 task get 查阅");
        check(runtime.tasks(8).contains(host), "结算后的承载记录仍出现在任务列表里");

        // 原生重生没能发出：换发新决策编号重新挂起，旧编号的迟到答复必须被拒；随后按应用成功收尾。
        IntentTaskRecord host2 = runtime.openDeathRecoveryDecision(false, false, new JsonObject());
        UUID firstId = host2.decisionSnapshot().id();
        check(host2.answer(firstId, "respawn", new JsonObject()), "第二次死亡的决策应可答复");
        runtime.requestDeathDecision(host2, false, false, new JsonObject());
        UUID secondId = host2.decisionSnapshot().id();
        check(!firstId.equals(secondId), "重挂决策必须换新编号");
        check(host2.answer(secondId, "respawn", new JsonObject()), "新编号必须可答复");
        check(!host2.answer(firstId, "respawn", new JsonObject()),
                "旧编号的迟到答复必须被拒绝，不能落到新问题上");
        runtime.finishDeathRecovery(host2, TaskState.SUCCESS,
                new TaskResult(true, "Native respawn was requested.", false, false, Map.of()), 15);
        check(host2.terminalSnapshot() != null, "重试成功后承载记录必须终态化");

        // 人工点击重生解决死亡：还挂着答复的承载记录被作废并结算，终态后不再接受任何答复。
        IntentTaskRecord host3 = runtime.openDeathRecoveryDecision(false, false, new JsonObject());
        UUID pendingId = host3.decisionSnapshot().id();
        runtime.finishSupersededDeathRecovery(20);
        check(host3.terminalSnapshot() != null
                        && host3.terminalSnapshot().state() == TaskState.CANCELLED,
                "人工重生后承载记录必须以取消结算");
        check(host3.decisionSnapshot() == null, "作废后不得遗留待答决策");
        check(!host3.answer(pendingId, "respawn", new JsonObject()),
                "作废后的迟到答复必须被拒绝");

        // 运行时级授权键：任何能力都可携带 auto_respawn / recover_after_death，未知键照旧拒绝。
        SemanticGoalContract.validate(rememberGoal(), Set.of("maicraft:remember_place"));
        try {
            SemanticGoalContract.validate(unknownKeyGoal(), Set.of("maicraft:remember_place"));
            check(false, "未知参数键必须照旧被拒绝");
        } catch (RuntimeException expected) {
            // 契约拒绝未知键是既有行为，这里只验证白名单没有放大成全键放行。
        }
        System.out.println("StandaloneDeathRecoveryTest: passed");
    }

    private static Goal rememberGoal() {
        return new Goal("maicraft:remember_place", "记住基地", null,
                "{\"auto_respawn\":true,\"recover_after_death\":true}", "{}", List.of(), List.of());
    }

    private static Goal unknownKeyGoal() {
        return new Goal("maicraft:remember_place", "记住基地", null,
                "{\"auto_respawn\":true,\"made_up_key\":true}", "{}", List.of(), List.of());
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
