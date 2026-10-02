// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.progression;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;

/**
 * 里程碑聚合层必须把子任务回执的卡点事实透传到对外回执：blocked_facts 保持原键名顶层出现，
 * 其余子证据聚合在 child_evidence 下；聚合层自己的 issue_code 语义不被替换。
 * 回归 009：blocked_facts 曾在 ReachMilestone 外层聚合处被整体丢弃，唯一对外入口看不到诊断。
 */
public final class ReachMilestoneEvidencePassThroughTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        aggregateKeepsChildBlockedFacts(memory);
        childWithoutBlockedFactsStillSurfaced(memory);
        missingChildResultStaysQuiet(memory);
        System.out.println("ReachMilestoneEvidencePassThroughTest: passed");
    }

    private static void aggregateKeepsChildBlockedFacts(Unsafe memory) throws Exception {
        Map<String, Object> blockedFacts =
                Map.of("phase", "locate", "survey_radius", 128, "feet", List.of(1.5, 64.0, 2.5));
        Map<String, Object> childData = new LinkedHashMap<>();
        childData.put("issue_code", "portal_site_unavailable");
        childData.put("failure_type", "target_lost");
        childData.put("requires_decision", true);
        childData.put("blocked_facts", blockedFacts);
        childData.put("target_item_family", "minecraft:obsidian");
        Map<String, Object> data = aggregateFailure(memory, childData);
        check("stronghold_progress_unavailable".equals(data.get("issue_code")),
                "the aggregate issue code keeps its own semantics");
        check(blockedFacts.equals(data.get("blocked_facts")),
                "child blocked_facts must surface at the top level unchanged, not be dropped");
        @SuppressWarnings("unchecked")
        var childEvidence = (Map<String, Object>) data.get("child_evidence");
        check(childEvidence != null && "minecraft:obsidian".equals(childEvidence.get("target_item_family")),
                "other child diagnostic evidence must stay visible under child_evidence");
        check(!childEvidence.containsKey("issue_code") && !childEvidence.containsKey("failure_type")
                        && !childEvidence.containsKey("requires_decision") && !childEvidence.containsKey("blocked_facts"),
                "keys the aggregate already expresses must not be duplicated");
    }

    private static void childWithoutBlockedFactsStillSurfaced(Unsafe memory) throws Exception {
        Map<String, Object> data = aggregateFailure(memory,
                Map.of("target_item_family", "minecraft:ender_eye", "attempts", 3));
        check(!data.containsKey("blocked_facts"),
                "a child without blocked facts must not invent an empty one");
        @SuppressWarnings("unchecked")
        var childEvidence = (Map<String, Object>) data.get("child_evidence");
        check(childEvidence != null && Integer.valueOf(3).equals(childEvidence.get("attempts")),
                "child evidence without blocked_facts still reaches the caller");
    }

    private static void missingChildResultStaysQuiet(Unsafe memory) throws Exception {
        Map<String, Object> data = aggregateFailure(memory, null);
        check(!data.containsKey("blocked_facts") && !data.containsKey("child_evidence"),
                "a missing child result must not fabricate evidence");
    }

    /** 驱动私有聚合失败路径（STRUCTURE_SEARCH 走通用聚合分支，不触碰世界与子任务工厂）。 */
    private static Map<String, Object> aggregateFailure(Unsafe memory, Map<String, Object> childData)
            throws Exception {
        Class<?> taskClass = ReachMilestoneCompanionTask.class;
        Object task = memory.allocateInstance(taskClass);
        field(AbstractCompanionTask.class, "r").set(task, new ReachMilestoneTaskRecord(
                "mcp-evidence", 100000, ReachMilestoneTaskRecord.Milestone.NETHER,
                4096, 128, 20F, false, false, false, List.of(), null, List.of()));
        Method handle = null;
        for (Method candidate : taskClass.getDeclaredMethods()) {
            if (candidate.getName().equals("handleChildFailure")) { handle = candidate; break; }
        }
        if (handle == null) throw new NoSuchMethodException("handleChildFailure");
        handle.setAccessible(true);
        Class<?> purpose = handle.getParameterTypes()[0];
        @SuppressWarnings("unchecked")
        Enum<?> structureSearch = Enum.valueOf((Class<? extends Enum>) purpose, "STRUCTURE_SEARCH");
        Object[] arguments = new Object[handle.getParameterCount()];
        arguments[0] = structureSearch;
        arguments[3] = childData == null ? null : TaskResult.fail("child stopped", childData);
        TaskState state = (TaskState) handle.invoke(task, arguments);
        check(state == TaskState.FAILED, "an aggregate child failure must fail the milestone task");
        Method resultData = taskClass.getDeclaredMethod("resultData");
        resultData.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resultData.invoke(task);
        return data;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field found = type.getDeclaredField(name); found.setAccessible(true); return found; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
