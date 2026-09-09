// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt.Footprint;

/** 已接受的任务步骤和进度逐项往返保存，不执行任何游戏动作。 */
public final class TaskStepPersistenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (int count : List.of(255, 256, 257, 288)) {
            Goal goal = sequence(count, "initial");
            Plan plan = IntentRuntime.get().compile(goal, 0);
            var task = new IntentTaskRecord(UUID.randomUUID(), plan.id(), goal);
            check(plan.steps().size() == count, "the plan must already accept every requested step");
            checkRoundTrip(task);
            for (int i = 0; i < count - 1; i++) finishStep(task);
            checkRoundTrip(task);
            task.insertRecovery(sequence(33, "recovery"));
            task.replaceCurrent(sequence(34, "replacement"));
            checkRoundTrip(task);
            if (count == 288) diskRoundTrip(task);
        }
        System.out.println("TaskStepPersistenceTest: all accepted steps and progress survive beyond 256 entries");
    }

    private static IntentStateCodec.TaskSnapshot checkRoundTrip(IntentTaskRecord task) {
        JsonObject encoded = encode(task, "step-test");
        JsonObject stored = encoded.getAsJsonArray("tasks").get(0).getAsJsonObject();
        check(stored.getAsJsonArray("steps").size() == task.steps().size(),
                "saving must not truncate an accepted " + task.steps().size() + "-step task");
        check(stored.getAsJsonArray("completed_steps").size() == task.stepIndex(),
                "completed steps beyond 256 must retain their results");
        check(stored.getAsJsonArray("internal_positions").size() == task.stepIndex()
                        && stored.getAsJsonArray("internal_area_protections").size() == task.stepIndex(),
                "all verified per-step receipts must be kept with the full progress");
        var decoded = IntentStateCodec.decode(encoded).tasks().getFirst();
        assertSnapshot(task, decoded);
        var restored = IntentTaskRecord.restored(decoded.id(), decoded.planId(), decoded.goal(), "step-test",
                decoded.steps(), decoded.stepIndex(), decoded.completed(), decoded.internalPositions(),
                decoded.internalAreaProtections(), decoded.attempts(), decoded.decision(), decoded.pendingAnswer(),
                decoded.terminal(), 100);
        check(restored.steps().equals(task.steps()) && restored.stepIndex() == task.stepIndex()
                        && restored.stepResults().equals(task.stepResults()),
                "restoration must resume at the saved step without losing inserted or replaced work");
        return decoded;
    }

    private static void assertSnapshot(IntentTaskRecord task, IntentStateCodec.TaskSnapshot decoded) {
        check(decoded.steps().equals(task.steps()) && decoded.stepIndex() == task.stepIndex(),
                "step ordering and progress must survive decoding even after step 256");
        check(decoded.completed().equals(task.stepResults()), "all completion records must round-trip");
        check(decoded.internalPositions().equals(task.internalPositionReceipts())
                        && decoded.internalAreaProtections().equals(task.internalAreaProtectionReceipts()),
                "positions and protected footprints must retain their exact step association");
    }

    private static void diskRoundTrip(IntentTaskRecord task) throws Exception {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(workspace, "step-persistence-");
        try {
            var identity = new StateIdentity("0".repeat(64), directory);
            new IntentStateStore().saveAsync(identity, encode(task, identity.key())).get(5, TimeUnit.SECONDS);
            var loaded = new IntentStateStore().load(identity);
            check(loaded.status() == IntentStateStore.Status.LOADED, "the completed checkpoint must load from disk");
            assertSnapshot(task, IntentStateCodec.decode(loaded.root()).tasks().getFirst());
        } finally {
            if (!directory.toRealPath().startsWith(workspace.toRealPath()))
                throw new AssertionError("test cleanup escaped its workspace");
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void finishStep(IntentTaskRecord task) {
        int index = task.stepIndex();
        Goal step = task.steps().get(index);
        task.retainInternalStepPosition(index, new Goal.WorldPosition(index, 64, 0, "minecraft:overworld"));
        long cell = new BlockPos(index, 64, 0).asLong();
        task.retainInternalAreaProtections(index, List.of(new Footprint("area " + index,
                "minecraft:overworld", List.of(cell), List.of(cell))));
        task.addStepResult(new IntentTaskRecord.StepSnapshot(index, step.ability(), true, "completed " + index, "{}"));
    }

    private static JsonObject encode(IntentTaskRecord task, String identity) {
        return IntentStateCodec.encode(identity, List.of(), List.of(task), Map.of(), List.of());
    }

    private static Goal sequence(int count, String prefix) {
        List<Goal> groups = new ArrayList<>();
        for (int start = 0; start < count; start += 32) {
            List<Goal> children = new ArrayList<>();
            for (int index = start; index < Math.min(count, start + 32); index++) {
                children.add(new Goal("maicraft:wait_for_condition", prefix + " " + index, null,
                        "{\"condition\":\"daytime\"}", "{}", List.of(), List.of()));
            }
            groups.add(new Goal("maicraft:sequence", "group " + start, null, "{}", "{}", List.of(), children));
        }
        return new Goal("maicraft:sequence", prefix, null, "{}", "{}", List.of(), groups);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
