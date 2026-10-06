// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 编译型任务规划期的守卫契约：选址勘察停滞时进度记分牌持续出心跳（phase + calc +
 * 已规划秒数），超过宽上限按 planning_stall 如实收场并携带阶段名与已等待时长——
 * 不再有零事件零终态、只能 operator cancel 的静默楔死。
 */
public final class PortalPlanningGuardTest {
    public static void main(String[] args) throws Exception {
        surveyReportsPlanningHeartbeat();
        surveyExceedsBoundIntoHonestFailure();
        System.out.println("PortalPlanningGuardTest: survey planning heartbeat and bounded failure passed");
    }

    /** 用黑曜石填满候选选址区：勘察永远得不到可用场址，规划期稳定持续到守卫上限。 */
    private static void sealCandidateVolume(InteractionWorldTestHarness world) {
        // 假世界只加载区块 (0,0)：候选区其余部分本就因未加载而不可用，填满加载区即可。
        for (int x = 0; x <= 15; x++)
            for (int z = 0; z <= 15; z++)
                for (int y = 0; y <= 8; y++)
                    world.set(new BlockPos(x, y, z), Blocks.OBSIDIAN.defaultBlockState());
    }

    private static PortalPreparationTask netherSurveyTask(InteractionWorldTestHarness world, long limitTicks) {
        var policy = new PortalPreparationPolicy(true, false, false, 128,
                MaterialPolicy.INVENTORY_ONLY, java.util.List.of(), java.util.List.of());
        var record = new PortalPreparationTaskRecord("planning-guard", 1_000_000,
                "minecraft:the_nether", 32, true, policy);
        var task = new PortalPreparationTask(world.player, record,
                org.maiwithu.maicraft.task.TaskFactory::create, limitTicks);
        task.start(world.player);
        return task;
    }

    /** 场景①：勘察挂起期记分牌持续可读——phase=survey、calc 随扫描单调增长、带已规划秒数。 */
    private static void surveyReportsPlanningHeartbeat() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            sealCandidateVolume(world);
            var task = netherSurveyTask(world, 10_000);
            Map<String, Object> first = null;
            Map<String, Object> later = null;
            for (int i = 0; i < 60; i++) {
                world.nextTick();
                TargetIndex.clientTick(world.level);
                TaskState state = task.tick(world.player);
                check(state == TaskState.RUNNING, "healthy survey keeps running during the heartbeat window");
                if (i == 20) first = task.progress();
                if (i == 59) later = task.progress();
            }
            check("survey".equals(first.get("phase")), "survey phase is reported as the standard phase key");
            check(first.get("calc") instanceof Integer calc && calc >= 1,
                    "planning scoreboard carries the survey scan counter");
            check(first.get("planning_seconds") instanceof Number, "heartbeat data carries planned-for seconds");
            check(((Number) later.get("calc")).longValue() > ((Number) first.get("calc")).longValue(),
                    "scan counter grows monotonically so the gate republishes every floor interval");
            check(((Number) later.get("planning_seconds")).longValue() >= ((Number) first.get("planning_seconds")).longValue(),
                    "planned-for seconds advance with the survey");
            task.result(TaskState.FAILED);
        }
    }

    /** 场景②：勘察超宽上限走如实终态失败——阶段名与已等待时长进回执，不静默不悬挂。 */
    private static void surveyExceedsBoundIntoHonestFailure() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            sealCandidateVolume(world);
            var task = netherSurveyTask(world, 100);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 400 && state == TaskState.RUNNING; i++) {
                world.nextTick();
                TargetIndex.clientTick(world.level);
                state = task.tick(world.player);
            }
            check(state == TaskState.FAILED, "exceeding the survey bound ends the task instead of wedging");
            var data = task.result(state).data();
            check("portal_survey_planning_timeout".equals(data.get("issue_code")),
                    "failure names the planning timeout");
            check("planning_stall".equals(data.get("failure_type")),
                    "planning timeout is reported as planning_stall, not target loss");
            @SuppressWarnings("unchecked")
            Map<String, Object> facts = (Map<String, Object>) data.get("blocked_facts");
            check(facts != null && "survey".equals(facts.get("phase")),
                    "failure facts carry the phase name");
            check(facts != null && facts.get("planned_for_seconds") instanceof Number waited
                            && waited.longValue() >= 5,
                    "failure facts carry the waited duration in seconds");
        }
    }
}
