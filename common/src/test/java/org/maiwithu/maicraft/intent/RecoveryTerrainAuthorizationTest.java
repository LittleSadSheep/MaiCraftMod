// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * 决策答复里的地形授权在同一条任务链内共享：recover 插入的前置步骤与重试的原步骤不再对同一授权重问一轮。
 * 显式给出的 may_alter_terrain（含 false）不被覆盖，授权也不跨任务传播。
 */
public final class RecoveryTerrainAuthorizationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        stepInheritsChainAuthorization(); explicitValuesAreKept(); recordInsertsAuthorizedPrereq();
        System.out.println("RecoveryTerrainAuthorizationTest: passed");
    }
    private static final Goal.SemanticTarget NOTHING = null;

    // 授权只在一侧给出时，缺省的另一侧补上同链授权，已表态的一侧保持原样。
    private static void stepInheritsChainAuthorization() {
        Goal harvest = goal("maicraft:harvest_block", "{\"may_alter_terrain\":true}");
        Goal travel = goal("maicraft:travel", "{}");
        var shared = IntentTask.shareTerrainAuthorization(harvest, travel);
        check(shared.answeredGoal().parameters().get("may_alter_terrain").getAsBoolean(),
                "authorized main step passes terrain permission to the inserted recovery step");
        check(shared.failedStep() == harvest, "the already authorized step is unchanged");

        Goal unHarvest = goal("maicraft:harvest_block", "{}");
        Goal authorizedTravel = goal("maicraft:travel", "{\"may_alter_terrain\":true}");
        var backShare = IntentTask.shareTerrainAuthorization(unHarvest, authorizedTravel);
        check(backShare.failedStep().parameters().get("may_alter_terrain").getAsBoolean(),
                "the recovery answer's authorization also covers the failed main step");
        check(backShare.answeredGoal() == authorizedTravel, "the answered goal itself is unchanged");

        var untouched = IntentTask.shareTerrainAuthorization(goal("maicraft:travel", "{}"), goal("maicraft:travel", "{}"));
        check(!untouched.failedStep().parameters().has("may_alter_terrain")
                        && !untouched.answeredGoal().parameters().has("may_alter_terrain"),
                "without any authorization on the chain nothing is invented");
    }

    // 任一侧显式写出 may_alter_terrain（含 false）都算表过态：更窄或更宽的授权范围仍走各自的如实询问。
    private static void explicitValuesAreKept() {
        var refused = IntentTask.shareTerrainAuthorization(
                goal("maicraft:harvest_block", "{\"may_alter_terrain\":true}"),
                goal("maicraft:travel", "{\"may_alter_terrain\":false}"));
        check(!refused.answeredGoal().parameters().get("may_alter_terrain").getAsBoolean(),
                "an explicit refusal on the recovery step is not overridden");
        var narrow = IntentTask.shareTerrainAuthorization(
                goal("maicraft:harvest_block", "{\"may_alter_terrain\":false}"),
                goal("maicraft:travel", "{\"may_alter_terrain\":true}"));
        check(!narrow.failedStep().parameters().get("may_alter_terrain").getAsBoolean(),
                "an explicit refusal on the main step is not overridden");
    }

    // 走真实的任务单路径：授权共享后插入的前置步骤带着授权排在原步骤之前，原步骤参数同步写回。
    private static void recordInsertsAuthorizedPrereq() {
        var record = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(),
                goal("maicraft:harvest_block", "{\"may_alter_terrain\":true}"));
        check(record.stepIndex() == 0 && record.steps().size() == 1, "the plan starts on its single step");
        Goal recovery = IntentTask.shareTerrainAuthorization(
                record.steps().get(0), goal("maicraft:travel", "{}")).answeredGoal();
        record.insertRecovery(recovery);
        check(record.steps().size() == 2 && record.stepIndex() == 0, "the recovery step is inserted before the failed step");
        check(record.steps().get(0).parameters().get("may_alter_terrain").getAsBoolean(),
                "the inserted prerequisite carries the chain authorization");
        check("maicraft:harvest_block".equals(record.steps().get(1).ability())
                        && record.steps().get(1).parameters().get("may_alter_terrain").getAsBoolean(),
                "the main step follows with its own authorization intact");
    }

    private static Goal goal(String ability, String parametersJson) {
        return new Goal(ability, "test outcome", NOTHING, parametersJson, "{}", java.util.List.of(), java.util.List.of());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
