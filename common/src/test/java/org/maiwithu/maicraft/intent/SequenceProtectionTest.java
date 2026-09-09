// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt.Footprint;

/** 检查分组范围最终进入真实导航保护边界，并经恢复、替换与持久化保留下来。 */
public final class SequenceProtectionTest {
    private static final BlockPos FARM = new BlockPos(1, 1, 1), GROVE = new BlockPos(2, 1, 1);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Goal leaf = waitGoal("work in the group");
        Goal root = sequence("{}", waitGoal("observe areas"),
                sequence("{\"protected_labels\":[\"Farm\"]}",
                        sequence("{\"protected_labels\":[\"grove\",\"Farm\"]}", leaf)),
                waitGoal("independent sibling"));
        JsonObject original = root.toJson();
        IntentRuntime.get().compile(root, 0);
        var record = new IntentTaskRecord(UUID.randomUUID(), null, root);
        record.retainInternalAreaProtections(0, List.of(
                new Footprint("farm", "minecraft:overworld", List.of(FARM.asLong()), List.of(FARM.asLong())),
                new Footprint("grove", "minecraft:overworld", List.of(GROVE.asLong()), List.of(GROVE.asLong()))));
        finishStep(record);
        try (var f = new InteractionWorldTestHarness()) {
            assertProtection(f, record, true);
            check(root.toJson().equals(original) && record.steps().get(1).toJson().equals(leaf.toJson()),
                    "scope metadata must not rewrite public goal parameters or the original request");
            IntentRuntime.get().validateGoal(record.steps().get(1));
            record.steps().set(1, record.steps().get(1).withParameters(leaf.parameters()).withTarget(null));
            assertProtection(f, record, true);
            assertProtection(f, roundTrip(record), true);

            var legacy = encoded(record);
            legacy.getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonArray("steps")
                    .forEach(step -> step.getAsJsonObject().remove("inherited_protected_labels"));
            assertProtection(f, restore(IntentStateCodec.decode(legacy).tasks().getFirst()), true);

            record.insertRecovery(sequence("{}", waitGoal("first prerequisite"), waitGoal("second prerequisite")));
            assertProtection(f, record, true);
            finishStep(record);
            record.replaceCurrent(sequence("{}", waitGoal("replacement one"), waitGoal("replacement two")));
            record = roundTrip(record);
            while (record.stepIndex() < record.steps().size() - 1) {
                assertProtection(f, record, true);
                finishStep(record);
            }
            assertProtection(f, record, false);
            check(!NavigationSafetyContext.protectsMutation(FARM), "task scope must end when its callback returns");
        }
        System.out.println("SequenceProtectionTest: nested scope survives retries and checkpoints without reaching siblings");
    }

    private static void assertProtection(InteractionWorldTestHarness f, IntentTaskRecord record, boolean expected)
            throws Exception {
        var task = new IntentTask(f.player, record, null);
        var boundary = IntentTask.class.getDeclaredMethod("withExplicitAreaProtection", Supplier.class);
        boundary.setAccessible(true);
        boundary.invoke(task, (Supplier<Void>) () -> {
            for (BlockPos pos : List.of(FARM, GROVE)) {
                check(NavigationSafetyContext.protectsMutation(pos) == expected,
                        "nested labels must protect terrain only for their own steps: " + record.stepIndex());
                check(NavigationSafetyContext.forbidsBody(pos) == expected,
                        "the same scope must protect movement through the measured area");
            }
            return null;
        });
    }

    private static IntentTaskRecord roundTrip(IntentTaskRecord record) {
        return restore(IntentStateCodec.decode(encoded(record)).tasks().getFirst());
    }

    private static JsonObject encoded(IntentTaskRecord record) {
        return IntentStateCodec.encode("scope-test", List.of(), List.of(record), Map.of(), List.of());
    }

    private static IntentTaskRecord restore(IntentStateCodec.TaskSnapshot s) {
        return IntentTaskRecord.restored(s.id(), s.planId(), s.goal(), "scope-test", s.steps(), s.stepIndex(),
                s.completed(), s.internalPositions(), s.internalAreaProtections(), s.attempts(),
                s.decision(), s.pendingAnswer(), s.terminal(), 100);
    }

    private static void finishStep(IntentTaskRecord record) {
        Goal step = record.steps().get(record.stepIndex());
        record.addStepResult(new IntentTaskRecord.StepSnapshot(record.stepIndex(), step.ability(), true, "done", "{}"));
    }

    private static Goal waitGoal(String outcome) {
        return new Goal("maicraft:wait_for_condition", outcome, null,
                "{\"condition\":\"daytime\"}", "{}", List.of(), List.of());
    }

    private static Goal sequence(String parameters, Goal... children) {
        return new Goal("maicraft:sequence", "ordered work", null, parameters, "{}", List.of(), List.of(children));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
