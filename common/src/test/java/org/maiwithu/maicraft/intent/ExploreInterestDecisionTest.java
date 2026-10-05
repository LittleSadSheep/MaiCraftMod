// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;

/**
 * 兴趣决策的全链路在无头环境能覆盖到的部分：记录层状态机、决策快照内容，
 * 以及目标参数经序列化往返后声明不丢。伴随任务与调度器的实机接线仍需实机验证。
 */
public final class ExploreInterestDecisionTest {
    public static void main(String[] args) {
        recordStateMachine();
        answerGate();
        decisionSnapshot();
        goalParametersRoundTrip();
        System.out.println("ExploreInterestDecisionTest: passed");
    }

    private static void recordStateMachine() {
        var finding = new SemanticExploreTaskRecord.InterestFinding(
                "finding-1", "lava_pool", 100, 64, -200, "north-east", 137);
        var other = new SemanticExploreTaskRecord.InterestFinding(
                "finding-2", "lava_pool", 300, 64, -400, "east", 260);
        // 未声明兴趣时零置位；声明后才挂待询问。
        var plain = record(null);
        check(!plain.declaredInterest("lava_pool"), "no interests declared by default");
        check(!plain.noteInterestFinding(finding), "finding without declared interests never pends");
        check(!plain.hasPendingInterestFinding(), "no pending finding without interests");

        var record = record(List.of("lava_pool"));
        check(record.declaredInterest("lava_pool"), "declared interest is visible");
        check(record.noteInterestFinding(finding), "first matching finding pends");
        check(record.pendingInterestFinding().findingId().equals("finding-1"), "pending keeps the finding");
        check(!record.noteInterestFinding(other), "a second finding waits while one decision is open");
        check(!record.noteInterestFinding(finding), "the same finding never re-pends");

        UUID decisionId = UUID.randomUUID();
        record.beginInterestDecision(decisionId);
        check(record.isInterestDecision(decisionId), "the decision id routes back to this record");
        check(!record.isInterestDecision(UUID.randomUUID()), "foreign decision ids are refused");
        record.applyInterestAnswer("continue");
        check("continue".equals(record.consumeInterestAnswer()), "continue answer is consumed once");
        check(record.consumeInterestAnswer() == null, "the answer is consumed exactly once");
        check(!record.hasPendingInterestFinding(), "continue clears the pending finding");

        // 同一 finding 已在已问集合，恢复或重复观察都不会再问。
        check(!record.noteInterestFinding(finding), "answered findings stay in the asked set");
        check(record.noteInterestFinding(other), "a different finding may ask again");

        var stopping = record(List.of("lava_pool"));
        stopping.noteInterestFinding(finding);
        UUID stopDecision = UUID.randomUUID();
        stopping.beginInterestDecision(stopDecision);
        stopping.applyInterestAnswer("stop");
        check("stop".equals(stopping.consumeInterestAnswer()), "stop answer is consumed");
        // stop 保留待询问发现，收尾回执据此携带发现明细；主目标未核实不因 stop 而改变。
        check(stopping.hasPendingInterestFinding() && "lava_pool".equals(
                stopping.pendingInterestFinding().targetId()), "stop keeps the finding for the receipt");

        // 决策暂停期间伴随任务不被 tick、租期冻结在暂停时刻；答复时刻按当前时刻补一个整租期，
        // 恢复后第一刻的截止检查不得因模型思考时长把继续选择误判成超时。
        var paused = record(List.of("lava_pool"));
        check(paused.getDeadlineGameTime() == 1000L, "fresh record keeps its construction deadline");
        paused.renewAfterInterestAnswer(100_000L);
        check(paused.getDeadlineGameTime() == 100_000L + SemanticExploreTaskRecord.LEG_LEASE_TICKS,
                "answering renews the lease from the answer moment");
    }

    /**
     * 答复后的恢复闸：决策应答写回但伴随任务尚未消费的窗口内，中继必须放行 tick——
     * 应答的消费点在伴随任务里；且写回窗口内绝不能对同一待询问发现重发新决策
     * （实机事故：continue 与 stop 每答一次都换来同一 finding 的全新决策，
     * 四连重提 13b6913c→f0468be2→df49f7a2→478026fa，任务永远回不到推进态）。
     */
    private static void answerGate() {
        var finding = new SemanticExploreTaskRecord.InterestFinding(
                "finding-1", "lava_pool", 100, 64, -200, "north-east", 137);
        var record = record(List.of("lava_pool"));
        record.noteInterestFinding(finding);
        check(relay(record, false) == IntentTask.InterestRelayAction.ISSUE,
                "a fresh pending finding issues exactly one decision");
        UUID decisionId = UUID.randomUUID();
        record.beginInterestDecision(decisionId);
        check(relay(record, false) == IntentTask.InterestRelayAction.PARK
                && relay(record, true) == IntentTask.InterestRelayAction.PARK,
                "an open decision keeps the companion parked until the answer arrives");

        // continue 分支：答复写回即放行 tick，且同一发现不再换发新决策；伴随任务消费后推进恢复。
        record.applyInterestAnswer("continue");
        check(relay(record, false) == IntentTask.InterestRelayAction.PASS_THROUGH
                && record.interestDecisionId().equals(decisionId),
                "a written continue answer passes through without re-issuing the decision");
        check("continue".equals(record.consumeInterestAnswer()),
                "the resumed companion consumes the continue answer");
        check(!record.hasPendingInterestFinding() && record.interestDecisionId() == null,
                "continue clears the pending finding and the decision id");
        check(!record.noteInterestFinding(finding),
                "the answered finding stays in the asked set, no new decision can pend for it");

        // stop 分支：同样放行且不重提；消费后待询问发现保留，收尾回执据此携带发现明细。
        var stopping = record(List.of("lava_pool"));
        stopping.noteInterestFinding(finding);
        stopping.beginInterestDecision(UUID.randomUUID());
        stopping.applyInterestAnswer("stop");
        check(relay(stopping, false) == IntentTask.InterestRelayAction.PASS_THROUGH,
                "a written stop answer passes through without re-issuing the decision");
        check("stop".equals(stopping.consumeInterestAnswer()),
                "the resumed companion consumes the stop answer");
        check(stopping.hasPendingInterestFinding(),
                "stop keeps the finding so the final receipt can name it");
        check(stopping.interestDecisionId() == null && stopping.consumeInterestAnswer() == null,
                "the stop answer is fully consumed, nothing re-opens the decision");
    }

    /** 中继判定的测试缝：快照开关模拟语义决策是否仍开着。 */
    private static IntentTask.InterestRelayAction relay(
            SemanticExploreTaskRecord explore, boolean decisionSnapshotOpen) {
        return IntentTask.exploreInterestRelayAction(explore, decisionSnapshotOpen);
    }

    private static void decisionSnapshot() {
        var goal = new Goal("maicraft:explore", "找群系", null,
                "{\"biome_id\":\"modded:autumn_forest\",\"interests\":[\"lava_pool\"]}", "{}", List.of(), List.of());
        var finding = new SemanticExploreTaskRecord.InterestFinding(
                "finding-9", "lava_pool", 120, 64, -340, "north-east", 137);
        var snapshot = IntentTask.exploreInterestDecision(goal, UUID.randomUUID(), finding);
        check(snapshot.question().contains("lava_pool")
                && snapshot.question().contains("north-east")
                && snapshot.question().contains("137")
                && snapshot.question().contains("120,64,-340")
                && snapshot.question().contains("exploration: label"),
                "question names the finding, its direction, distance and exploration label");
        check(snapshot.options().size() == 2
                && snapshot.options().get(0).choice().equals("continue")
                && snapshot.options().get(1).choice().equals("stop"),
                "exactly continue and stop options are offered");
        JsonObject context = snapshot.context();
        check("explore_interest".equals(context.get("decision_kind").getAsString()),
                "context marks the decision kind");
        JsonObject detail = context.getAsJsonObject("finding");
        check(detail.get("finding_id").getAsString().equals("finding-9")
                && detail.get("target_id").getAsString().equals("lava_pool")
                && detail.get("x").getAsInt() == 120
                && detail.get("distance_blocks").getAsInt() == 137,
                "context carries the full finding detail");
    }

    private static void goalParametersRoundTrip() {
        // 任务单本体不进检查点；声明经由目标参数持久化，序列化往返后仍能通过恢复校验并接单。
        Goal original = new Goal("maicraft:explore", "找群系", null,
                "{\"biome_id\":\"modded:autumn_forest\",\"interests\":[\"lava_pool\"]}", "{}", List.of(), List.of());
        SemanticGoalContract.validate(original, IntentRuntime.KNOWN_ABILITIES);
        Goal restored = Goal.fromJson(original.toJson());
        // 恢复期只校验能力名在册；参数合法性由上面的新提交校验与执行适配器把关。
        if (!IntentRuntime.KNOWN_ABILITIES.contains(restored.ability()))
            throw new AssertionError("序列化往返后的能力名应仍在册");
        IntentAction.Tool adapted = (IntentAction.Tool) AbilityAdapter.adapt(restored, null, null);
        check(adapted.arguments().getAsJsonArray("interests").size() == 1
                && adapted.arguments().getAsJsonArray("interests").get(0).getAsString().equals("lava_pool"),
                "interests survive goal serialization and re-adaptation");
    }

    private static SemanticExploreTaskRecord record(List<String> interests) {
        return new SemanticExploreTaskRecord(
                "interest-test", 1000L, "survey", 128, false,
                org.maiwithu.maicraft.core.pathing.transport.TransportMode.AUTO,
                null, null, null, interests);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
