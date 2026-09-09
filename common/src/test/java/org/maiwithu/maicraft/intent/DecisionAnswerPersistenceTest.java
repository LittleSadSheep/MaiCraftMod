// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.task.TaskState;

/** 已接受的答复是待执行请求；保存不能按公开诊断结果的规则删字段或缩短蓝图。 */
public final class DecisionAnswerPersistenceTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Goal current = new Goal("maicraft:travel", "reach camp", null,
                "{\"destination\":{\"x\":0,\"y\":64,\"z\":0}}", "{}", List.of(), List.of());
        Goal destination = new Goal("maicraft:travel", "reach the revised camp",
                new Goal.SemanticTarget("coordinates", null, new Goal.WorldPosition(-10, 72, 25, "minecraft:overworld"), null),
                "{\"exact\":true}", "{}", List.of(), List.of());
        for (String choice : List.of("replace_goal", "recover")) {
            JsonObject details = new JsonObject(); details.add("goal", destination.toJson());
            roundTrip(current, choice, details);
        }
        JsonObject updates = JsonParser.parseString(
                "{\"destination\":{\"x\":10.5,\"y\":null,\"z\":-20.25,\"dimension\":\"minecraft:overworld\"},\"horizontal_radius\":0}")
                .getAsJsonObject();
        roundTrip(current, "retry", updates);
        JsonObject wrapped = new JsonObject(); wrapped.add("parameters", updates);
        roundTrip(current, "retry", wrapped);
        roundTrip(current, "skip", new JsonObject());

        Goal blueprint = blueprint();
        JsonObject details = new JsonObject(); details.add("goal", blueprint.toJson());
        roundTrip(current, "replace_goal", details);
        details = new JsonObject(); details.add("parameters", blueprint.parameters());
        roundTrip(blueprint, "retry", details);
        rejectsMalformedCheckpoint(current);
        System.out.println("DecisionAnswerPersistenceTest: accepted coordinates, parameter updates and blueprints survive saves");
    }

    private static void roundTrip(Goal current, String choice, JsonObject details) {
        IntentRuntime runtime = IntentRuntime.get();
        var record = new IntentTaskRecord(UUID.randomUUID(), null, current);
        UUID decisionId = UUID.randomUUID();
        record.requestDecision(new IntentTaskRecord.DecisionSnapshot(decisionId, "choose how to continue",
                List.of(new IntentTaskRecord.DecisionOption(choice, "continue")), "{}"), 1);
        runtime.validateDecisionAnswer(record, choice, details);
        check(record.answer(decisionId, choice, details), "a valid answer must be accepted before saving");
        record.addAttempt(new IntentTaskRecord.AttemptSnapshot(0, current, TaskState.FAILED, "prior attempt",
                "{\"position\":{\"x\":1,\"y\":2,\"z\":3},\"slot\":4,\"visible\":true}", 0));
        JsonObject encoded = IntentStateCodec.encode("answer-test", List.of(), List.of(record), Map.of(), List.of());
        var stored = encoded.getAsJsonArray("tasks").get(0).getAsJsonObject();
        check(stored.getAsJsonObject("pending_answer").getAsJsonObject("details").equals(details),
                "saving an accepted " + choice + " answer must retain every supplied field");
        var result = stored.getAsJsonArray("attempts").get(0).getAsJsonObject().getAsJsonObject("result");
        check(!result.has("position") && !result.has("slot") && result.get("visible").getAsBoolean(),
                "preserving a request must not disable filtering of diagnostic results");
        var s = IntentStateCodec.decode(encoded).tasks().getFirst();
        var restored = IntentTaskRecord.restored(s.id(), s.planId(), s.goal(), "answer-test", s.steps(), s.stepIndex(),
                s.completed(), s.internalPositions(), s.internalAreaProtections(), s.attempts(),
                s.decision(), s.pendingAnswer(), s.terminal(), 100);
        var answer = restored.takeAnswer();
        check(answer.decisionId().equals(decisionId) && answer.choice().equals(choice) && answer.details().equals(details),
                "decoding and restoring must retain the answer that was actually accepted");
        runtime.validateDecisionAnswer(restored, choice, answer.details());
        check(restored.takeAnswer() == null, "a restored answer is consumed once");
    }

    private static Goal blueprint() {
        JsonObject blueprint = new JsonObject(); blueprint.addProperty("schema_version", 1);
        JsonArray blocks = new JsonArray();
        for (int i = 0; i < 300; i++) {
            JsonObject cell = new JsonObject();
            JsonArray offset = new JsonArray(); offset.add(i % 30); offset.add(0); offset.add(i / 30);
            cell.add("offset", offset); cell.addProperty("block_id", "minecraft:stone"); blocks.add(cell);
        }
        blueprint.add("blocks", blocks);
        JsonObject parameters = new JsonObject(); parameters.add("blueprint", blueprint);
        return new Goal("maicraft:design_machine", "keep the complete design", null,
                parameters.toString(), "{}", List.of(), List.of());
    }

    private static void rejectsMalformedCheckpoint(Goal current) {
        var record = new IntentTaskRecord(UUID.randomUUID(), null, current);
        var encoded = IntentStateCodec.encode("answer-test", List.of(), List.of(record), Map.of(), List.of());
        var answer = new JsonObject(); answer.addProperty("decision_id", UUID.randomUUID().toString());
        answer.addProperty("choice", "retry"); answer.add("details", new JsonArray());
        encoded.getAsJsonArray("tasks").get(0).getAsJsonObject().add("pending_answer", answer);
        try {
            IntentStateCodec.decode(encoded);
            throw new AssertionError("malformed persisted answer must be rejected, not silently replaced");
        } catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
