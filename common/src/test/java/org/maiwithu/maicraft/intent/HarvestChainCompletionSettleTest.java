// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.task.TaskResult;

/**
 * harvest 链的完成确认与执行前校验分开：本链更早步骤已对同一格确认收获后，重复出现的
 * 同一格步骤按已达成结算，不再交给翻译把 air 当失配询问（187）。执行前就不匹配的目标
 * 没有本链成功证据，仍走原翻译如实失败。
 */
public final class HarvestChainCompletionSettleTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        duplicateRecoveryStepSettlesAsDone();
        unexecutedOrFailedEvidenceDoesNotSettle();
        differentCellOrAbilityDoesNotSettle();
        System.out.println("HarvestChainCompletionSettleTest: passed");
    }

    private static final String CELL = "{\"kind\":\"coordinates\",\"position\":{\"x\":-2900,\"y\":75,\"z\":-895}}";

    // 实机 86869b4f 形态：决策答复经 recover 插入同格前置步骤并成功挖掘，随后的原步骤必须按已达成结算。
    private static void duplicateRecoveryStepSettlesAsDone() {
        var record = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(), harvest(CELL, "{}"));
        record.insertRecovery(harvest(CELL, "{\"may_alter_terrain\":true}"));
        record.addStepResult(new IntentTaskRecord.StepSnapshot(
                0, "maicraft:harvest_block", true, "harvest completed", "{}"));
        TaskResult settled = IntentTask.settleAlreadyHarvestedStep(record, record.steps().get(1));
        check(settled != null && settled.success(),
                "the duplicated same-cell step settles as done after the chain's confirmed harvest");
        check(settled.data().get("already_harvested_by_step").equals(0),
                "the receipt points at the earlier step that did the work");
    }

    // 执行前的失配口径不变：没有更早成功证据（未执行、失败过）时仍交给翻译如实校验。
    private static void unexecutedOrFailedEvidenceDoesNotSettle() {
        var fresh = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(), harvest(CELL, "{}"));
        check(IntentTask.settleAlreadyHarvestedStep(fresh, fresh.steps().get(0)) == null,
                "a fresh plan has no harvest evidence and still translates normally");

        var failedFirst = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(), harvest(CELL, "{}"));
        failedFirst.addStepResult(new IntentTaskRecord.StepSnapshot(
                0, "maicraft:harvest_block", false, "target mismatch", "{}"));
        check(IntentTask.settleAlreadyHarvestedStep(failedFirst, failedFirst.steps().get(0)) == null,
                "an earlier failed attempt is not success evidence");
    }

    // 成功证据必须落在同一格、同一能力上，其余能力与目标形状不沾光。
    private static void differentCellOrAbilityDoesNotSettle() {
        var record = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(), harvest(CELL, "{}"));
        record.insertRecovery(harvest(CELL, "{\"may_alter_terrain\":true}"));
        record.addStepResult(new IntentTaskRecord.StepSnapshot(
                0, "maicraft:harvest_block", true, "harvest completed", "{}"));

        String otherCell = "{\"kind\":\"coordinates\",\"position\":{\"x\":-2900,\"y\":76,\"z\":-895}}";
        check(IntentTask.settleAlreadyHarvestedStep(record, harvest(otherCell, "{}")) == null,
                "a different cell still translates and validates on its own");

        var acquireRecord = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(), harvest(CELL, "{}"));
        acquireRecord.insertRecovery(acquire());
        acquireRecord.addStepResult(new IntentTaskRecord.StepSnapshot(
                0, "maicraft:acquire_items", true, "bought one", "{}"));
        check(IntentTask.settleAlreadyHarvestedStep(acquireRecord, acquireRecord.steps().get(1)) == null,
                "an earlier successful step of another ability is not a harvest confirmation");

        var landmarkRecord = new IntentTaskRecord(UUID.randomUUID(), UUID.randomUUID(),
                harvest("{\"kind\":\"landmark\",\"label\":\"home\"}", "{}"));
        check(IntentTask.settleAlreadyHarvestedStep(landmarkRecord, landmarkRecord.steps().get(0)) == null,
                "non-coordinate targets keep the ordinary translation path");
    }

    private static Goal harvest(String targetJson, String parametersJson) {
        return new Goal("maicraft:harvest_block", "test outcome",
                Goal.SemanticTarget.fromJson(com.google.gson.JsonParser.parseString(targetJson).getAsJsonObject()),
                parametersJson, "{}", java.util.List.of(), java.util.List.of());
    }

    private static Goal acquire() {
        return new Goal("maicraft:acquire_items", "test outcome", null,
                "{}", "{}", java.util.List.of(), java.util.List.of());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
