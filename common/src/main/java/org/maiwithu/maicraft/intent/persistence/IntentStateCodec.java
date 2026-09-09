// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.BlueprintGoalData;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.intent.Plan;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;

/** 把总任务、计划和地标转成可保存的 JSON，再从中恢复；不保存旧玩家的按键、菜单或正在走的路线。 */
public final class IntentStateCodec {
    public static final int MAX_PLANS = 128;
    public static final int MAX_TASKS = 256;
    public static final int MAX_REQUEST_KEYS = 512;
    public static final int MAX_LANDMARKS = 256;
    private static final int MAX_STEPS = 256;
    private static final int MAX_ATTEMPTS = 64;
    private static final int MAX_OPTIONS = 32;
    private static final int MAX_TEXT = 4_096;
    private static final int MAX_RESULT_DEPTH = 32;
    private static final Set<String> INTERNAL_KEYS = Set.of(
            "entity_id", "entity_ids", "requested_entity_ids", "defeated_entity_ids",
            "lost_entity_ids", "unreachable_entity_ids", "combat_by_entity",
            "entity_uuid", "entity_uuids",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot", "slots", "inventory_slots",
            "slot_clicks", "click_sequence", "route", "waypoints", "path_nodes",
            "block_ops", "placements", "cells", "receipt", "receipts",
            "exception", "stacktrace", "stack_trace",
            "x", "y", "z", "position", "center", "location", "destination", "bounds");
    private static final Set<String> GOAL_INTERNAL_KEYS = Set.of(
            "entity_id", "entity_ids", "entity_uuid", "entity_uuids",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot_clicks", "click_sequence",
            "inventory_slots", "route", "waypoints", "path_nodes", "block_ops",
            "placements", "cells", "receipt", "receipts", "exception",
            "stacktrace", "stack_trace");

    private IntentStateCodec() {}

    public record TaskSnapshot(
            UUID id,
            UUID planId,
            Goal goal,
            List<Goal> steps,
            int stepIndex,
            List<IntentTaskRecord.StepSnapshot> completed,
            Map<Integer, Goal.WorldPosition> internalPositions,
            Map<Integer, List<InternalAreaProtectionReceipt.Footprint>> internalAreaProtections,
            List<IntentTaskRecord.AttemptSnapshot> attempts,
            IntentTaskRecord.DecisionSnapshot decision,
            IntentTaskRecord.DecisionAnswer pendingAnswer,
            IntentTaskRecord.TerminalSnapshot terminal) {}

    public record Decoded(
            List<Plan> plans,
            List<TaskSnapshot> tasks,
            Map<String, UUID> requestKeys,
            List<IntentRuntime.Landmark> landmarks) {}

    /** 检查目标的 JSON 能否完整保存；这里检查每层内容的大小，没有检查组合目标展开后的总步数。 */
    public static void requirePersistableGoal(Goal goal) {
        safeGoal(goal);
    }

    public static JsonObject encode(
            String identityKey,
            Iterable<Plan> plans,
            Iterable<IntentTaskRecord> tasks,
            Map<String, UUID> requestKeys,
            Iterable<IntentRuntime.Landmark> landmarks) {
        // 保存文件版本、属于哪个世界、保存时间，再依次写计划、任务、重复请求编号和地标。
        JsonObject root = new JsonObject();
        root.addProperty("version", IntentStateStore.VERSION);
        root.addProperty("identity_key", identityKey);
        root.addProperty("saved_at_epoch_ms", System.currentTimeMillis());

        JsonArray planArray = new JsonArray();
        int planCount = 0;
        for (Plan plan : plans) {
            if (planCount++ >= MAX_PLANS) break;
            JsonObject value = new JsonObject();
            value.addProperty("id", plan.id().toString());
            value.add("goal", safeGoal(plan.goal()));
            value.addProperty("created_game_time", plan.createdGameTime());
            planArray.add(value);
        }
        root.add("plans", planArray);

        JsonArray taskArray = new JsonArray();
        int taskCount = 0;
        for (IntentTaskRecord task : tasks) {
            // 当前按传入顺序只保存前 MAX_TASKS 个；运行时需要自己保证重要任务没被排在这个范围外。
            if (taskCount++ >= MAX_TASKS) break;
            taskArray.add(encodeTask(task));
        }
        root.add("tasks", taskArray);

        JsonObject keys = new JsonObject();
        int keyCount = 0;
        for (Map.Entry<String, UUID> entry : requestKeys.entrySet()) {
            if (keyCount++ >= MAX_REQUEST_KEYS) break;
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getKey().length() > 256 || entry.getValue() == null) continue;
            keys.addProperty(entry.getKey(), entry.getValue().toString());
        }
        root.add("request_keys", keys);

        JsonArray landmarkArray = new JsonArray();
        int landmarkCount = 0;
        for (IntentRuntime.Landmark landmark : landmarks) {
            if (landmarkCount++ >= MAX_LANDMARKS) break;
            JsonObject value = new JsonObject();
            value.addProperty("label", bounded(landmark.label()));
            value.add("position", worldPosition(landmark.position()));
            value.addProperty("area_role", landmark.areaRole().id());
            landmarkArray.add(value);
        }
        root.add("landmarks", landmarkArray);
        return root;
    }

    private static JsonObject encodeTask(IntentTaskRecord task) {
        // 原始目标和实际步骤都保存：恢复过程中可能插入了“先找材料”等新步骤，不能只保存原始目标。
        JsonObject value = new JsonObject();
        value.addProperty("id", task.externalId().toString());
        if (task.planId() != null) value.addProperty("plan_id", task.planId().toString());
        value.add("goal", safeGoal(task.goal()));
        JsonArray steps = new JsonArray();
        task.steps().stream().limit(MAX_STEPS).forEach(step -> steps.add(safeGoal(step)));
        // 当前只保存前二百五十六步，但下面的 step_index 不会一起裁剪；过长任务可能丢步骤或无法恢复。
        value.add("steps", steps);
        value.addProperty("step_index", task.stepIndex());

        JsonArray completed = new JsonArray();
        task.stepResults().stream().limit(MAX_STEPS).forEach(step -> {
            JsonObject item = new JsonObject();
            item.addProperty("index", step.index());
            item.addProperty("ability", bounded(step.ability()));
            item.addProperty("success", step.success());
            item.addProperty("message", safeMessage(step.message()));
            item.add("result", safeObject(step.resultJson()));
            completed.add(item);
        });
        value.add("completed_steps", completed);

        // 已确认的位置单独保存，供“去前一步找到的地方”使用；不与会删掉坐标的公开结果混在一起。
        JsonArray internalPositions = new JsonArray();
        task.internalPositionReceipts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_STEPS)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("step_index", entry.getKey());
                    item.add("position", worldPosition(entry.getValue()));
                    internalPositions.add(item);
                });
        value.add("internal_positions", internalPositions);

        // 要保护哪些格子也单独保存，恢复后仍可用于导航和施工；文件整体仍受四 MiB 大小限制。
        JsonArray internalAreaProtections = new JsonArray();
        task.internalAreaProtectionReceipts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_STEPS)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("step_index", entry.getKey());
                    JsonArray footprints = new JsonArray();
                    for (InternalAreaProtectionReceipt.Footprint footprint : entry.getValue()) {
                        JsonObject encoded = new JsonObject();
                        if (footprint.semanticLabel() != null) {
                            encoded.addProperty("semantic_label", bounded(footprint.semanticLabel()));
                        }
                        if (footprint.dimension() != null) {
                            encoded.addProperty("dimension", bounded(footprint.dimension()));
                        }
                        encoded.add("protected_mutation_cells",
                                packedCells(footprint.protectedMutationCells()));
                        encoded.add("forbidden_body_cells",
                                packedCells(footprint.forbiddenBodyCells()));
                        footprints.add(encoded);
                    }
                    item.add("footprints", footprints);
                    internalAreaProtections.add(item);
                });
        value.add("internal_area_protections", internalAreaProtections);

        JsonArray attempts = new JsonArray();
        List<IntentTaskRecord.AttemptSnapshot> attemptValues = task.attempts();
        int from = Math.max(0, attemptValues.size() - MAX_ATTEMPTS);
        for (IntentTaskRecord.AttemptSnapshot attempt : attemptValues.subList(
                from, attemptValues.size())) {
            JsonObject item = new JsonObject();
            item.addProperty("step_index", attempt.stepIndex());
            item.add("goal", safeGoal(attempt.goal()));
            item.addProperty("state", attempt.state().name().toLowerCase());
            item.addProperty("message", safeMessage(attempt.message()));
            item.add("result", safeObject(attempt.resultJson()));
            item.addProperty("game_time", attempt.gameTime());
            attempts.add(item);
        }
        value.add("attempts", attempts);
        if (task.decisionSnapshot() != null) {
            value.add("decision", decision(task.decisionSnapshot()));
        }
        if (task.pendingAnswerSnapshot() != null) {
            // 答复可能已经收到了，但还没轮到执行；保存它以便回来后继续处理。
            // 这里目前用了公开结果的过滤规则，答复中合法目标的 position 等字段也会被删掉。
            IntentTaskRecord.DecisionAnswer answer = task.pendingAnswerSnapshot();
            JsonObject item = new JsonObject();
            item.addProperty("decision_id", answer.decisionId().toString());
            item.addProperty("choice", bounded(answer.choice()));
            item.add("details", safeObject(answer.detailsJson()));
            value.add("pending_answer", item);
        }
        if (task.terminalSnapshot() != null) {
            IntentTaskRecord.TerminalSnapshot terminal = task.terminalSnapshot();
            JsonObject item = new JsonObject();
            item.addProperty("state", terminal.state().name().toLowerCase());
            item.add("result", safeObject(terminal.resultJson()));
            item.addProperty("game_time", terminal.gameTime());
            value.add("terminal", item);
        }
        return value;
    }

    private static JsonObject decision(IntentTaskRecord.DecisionSnapshot decision) {
        // 保存正在等人回答的问题、允许的选项和背景；问题编号要保留，回来后旧编号的答复才能匹配。
        JsonObject value = new JsonObject();
        value.addProperty("id", decision.id().toString());
        value.addProperty("question", safeMessage(decision.question()));
        JsonArray options = new JsonArray();
        decision.options().stream().limit(MAX_OPTIONS).forEach(option -> {
            JsonObject item = new JsonObject();
            item.addProperty("choice", bounded(option.choice()));
            item.addProperty("description", bounded(option.description()));
            options.add(item);
        });
        value.add("options", options);
        value.add("context", safeObject(decision.contextJson()));
        return value;
    }

    public static Decoded decode(JsonObject root) {
        // 先恢复普通数据对象；是否属于当前世界、是否允许恢复执行，由状态存储层和运行时再判断。
        List<Plan> plans = new ArrayList<>();
        JsonArray planArray = array(root, "plans", MAX_PLANS);
        for (JsonElement element : planArray) {
            JsonObject value = element.getAsJsonObject();
            Goal goal = decodeGoal(value.getAsJsonObject("goal"));
            plans.add(new Plan(
                    UUID.fromString(text(value, "id")),
                    goal,
                    goal.executableSteps(),
                    longValue(value, "created_game_time", 0L)));
        }

        List<TaskSnapshot> tasks = new ArrayList<>();
        JsonArray taskArray = array(root, "tasks", MAX_TASKS);
        for (JsonElement element : taskArray) {
            tasks.add(decodeTask(element.getAsJsonObject()));
        }

        Map<String, UUID> keys = new LinkedHashMap<>();
        JsonObject keyObject = root.has("request_keys") && root.get("request_keys").isJsonObject()
                ? root.getAsJsonObject("request_keys") : new JsonObject();
        int keyCount = 0;
        for (Map.Entry<String, JsonElement> entry : keyObject.entrySet()) {
            if (keyCount++ >= MAX_REQUEST_KEYS) break;
            if (entry.getKey().isBlank() || entry.getKey().length() > 256
                    || !entry.getValue().isJsonPrimitive()) continue;
            keys.put(entry.getKey(), UUID.fromString(entry.getValue().getAsString()));
        }

        List<IntentRuntime.Landmark> landmarks = new ArrayList<>();
        for (JsonElement element : array(root, "landmarks", MAX_LANDMARKS)) {
            JsonObject value = element.getAsJsonObject();
            landmarks.add(new IntentRuntime.Landmark(
                    bounded(text(value, "label")),
                    decodePosition(value.getAsJsonObject("position")),
                    IntentRuntime.LandmarkAreaRole.fromPersisted(
                            value.has("area_role") && value.get("area_role").isJsonPrimitive()
                                    ? value.get("area_role").getAsString() : null)));
        }
        return new Decoded(
                List.copyOf(plans), List.copyOf(tasks),
                Map.copyOf(keys), List.copyOf(landmarks));
    }

    private static TaskSnapshot decodeTask(JsonObject value) {
        // 有保存下来的实际步骤就用它，没有才从原始目标重建；所以被截短但非空的列表不会自动补全。
        UUID id = UUID.fromString(text(value, "id"));
        UUID planId = value.has("plan_id")
                ? UUID.fromString(value.get("plan_id").getAsString()) : null;
        Goal goal = decodeGoal(value.getAsJsonObject("goal"));
        List<Goal> steps = new ArrayList<>();
        for (JsonElement element : array(value, "steps", MAX_STEPS)) {
            steps.add(decodeGoal(element.getAsJsonObject()));
        }
        if (steps.isEmpty()) steps.addAll(goal.executableSteps());
        // 旧存档若保留了原步骤顺序，可从原始树补回分组范围；已有的执行范围继续保留。
        List<Goal> declared = goal.executableSteps();
        if (steps.size() == declared.size() && java.util.stream.IntStream.range(0, steps.size())
                .allMatch(i -> steps.get(i).toJson().equals(declared.get(i).toJson()))) {
            for (int i = 0; i < steps.size(); i++) {
                steps.set(i, steps.get(i).withInheritedProtection(declared.get(i).inheritedProtectionLabels()));
            }
        }
        int stepIndex = integer(value, "step_index", 0);
        if (stepIndex < 0 || stepIndex > steps.size()) {
            // 当前做到哪一步不能超出已保存的步骤，否则整条任务记录不可信。
            throw new IllegalArgumentException("persisted semantic step index is outside bounds");
        }

        List<IntentTaskRecord.StepSnapshot> completed = new ArrayList<>();
        for (JsonElement element : array(value, "completed_steps", MAX_STEPS)) {
            JsonObject item = element.getAsJsonObject();
            int completedIndex = integer(item, "index", completed.size());
            if (completedIndex < 0 || completedIndex >= steps.size()) {
                throw new IllegalArgumentException(
                        "persisted completed semantic step index is outside bounds");
            }
            completed.add(new IntentTaskRecord.StepSnapshot(
                    completedIndex,
                    bounded(text(item, "ability")),
                    item.has("success") && item.get("success").getAsBoolean(),
                    safeMessage(text(item, "message")),
                    safeElement(item.get("result")).toString()));
        }
        if (completed.size() > stepIndex) {
            throw new IllegalArgumentException(
                    "persisted completed semantic steps exceed current progress");
        }

        Map<Integer, Goal.WorldPosition> internalPositions = new LinkedHashMap<>();
        for (JsonElement element : array(value, "internal_positions", MAX_STEPS)) {
            // 位置必须来自已经完成的步骤；正在尝试或失败的步骤不能授权后续任务使用其位置。
            JsonObject item = element.getAsJsonObject();
            int positionStepIndex = integer(item, "step_index", -1);
            if (positionStepIndex < 0 || positionStepIndex >= stepIndex) {
                throw new IllegalArgumentException(
                        "persisted internal position receipt is outside completed progress");
            }
            if (!item.has("position") || !item.get("position").isJsonObject()) {
                throw new IllegalArgumentException(
                        "persisted internal position receipt has no position");
            }
            if (internalPositions.put(positionStepIndex,
                    decodePosition(item.getAsJsonObject("position"))) != null) {
                throw new IllegalArgumentException(
                        "duplicate persisted internal position receipt");
            }
        }

        Map<Integer, List<InternalAreaProtectionReceipt.Footprint>>
                internalAreaProtections = new LinkedHashMap<>();
        for (JsonElement element : array(value, "internal_area_protections", MAX_STEPS)) {
            // 同样只恢复已经完成步骤测出的保护范围，并拒绝同一步重复保存多份记录。
            JsonObject item = element.getAsJsonObject();
            int protectionStepIndex = integer(item, "step_index", -1);
            if (protectionStepIndex < 0 || protectionStepIndex >= stepIndex) {
                throw new IllegalArgumentException(
                        "persisted internal area receipt is outside completed progress");
            }
            List<InternalAreaProtectionReceipt.Footprint> footprints = new ArrayList<>();
            for (JsonElement footprintElement : array(item, "footprints", MAX_STEPS)) {
                JsonObject footprint = footprintElement.getAsJsonObject();
                String label = footprint.has("semantic_label")
                        && footprint.get("semantic_label").isJsonPrimitive()
                        ? bounded(footprint.get("semantic_label").getAsString()) : null;
                String dimension = footprint.has("dimension")
                        && footprint.get("dimension").isJsonPrimitive()
                        ? bounded(footprint.get("dimension").getAsString()) : null;
                footprints.add(new InternalAreaProtectionReceipt.Footprint(
                        label, dimension,
                        decodePackedCells(footprint, "protected_mutation_cells"),
                        decodePackedCells(footprint, "forbidden_body_cells")));
            }
            if (internalAreaProtections.put(
                    protectionStepIndex, List.copyOf(footprints)) != null) {
                throw new IllegalArgumentException(
                        "duplicate persisted internal area receipt");
            }
        }

        List<IntentTaskRecord.AttemptSnapshot> attempts = new ArrayList<>();
        for (JsonElement element : array(value, "attempts", MAX_ATTEMPTS)) {
            JsonObject item = element.getAsJsonObject();
            int attemptStepIndex = integer(item, "step_index", 0);
            if (attemptStepIndex < 0 || attemptStepIndex >= steps.size()) {
                throw new IllegalArgumentException(
                        "persisted semantic attempt index is outside bounds");
            }
            attempts.add(new IntentTaskRecord.AttemptSnapshot(
                    attemptStepIndex,
                    decodeGoal(item.getAsJsonObject("goal")),
                    TaskState.valueOf(text(item, "state").toUpperCase()),
                    safeMessage(text(item, "message")),
                    safeElement(item.get("result")).toString(),
                    longValue(item, "game_time", 0L)));
        }
        IntentTaskRecord.DecisionSnapshot decision = value.has("decision")
                ? decodeDecision(value.getAsJsonObject("decision")) : null;
        IntentTaskRecord.DecisionAnswer answer = value.has("pending_answer")
                ? decodeAnswer(value.getAsJsonObject("pending_answer")) : null;
        IntentTaskRecord.TerminalSnapshot terminal = value.has("terminal")
                ? decodeTerminal(value.getAsJsonObject("terminal")) : null;
        return new TaskSnapshot(
                id, planId, goal, List.copyOf(steps), stepIndex,
                List.copyOf(completed), Map.copyOf(internalPositions),
                Map.copyOf(internalAreaProtections),
                List.copyOf(attempts),
                decision, answer, terminal);
    }

    private static JsonArray packedCells(List<Long> cells) {
        JsonArray result = new JsonArray();
        if (cells != null) for (Long cell : cells) if (cell != null) result.add(cell);
        return result;
    }

    private static List<Long> decodePackedCells(JsonObject value, String key) {
        // 每个 long 都是游戏把一格 x/y/z 打包后的值；这里只恢复列表，不去读取或改变世界方块。
        List<Long> result = new ArrayList<>();
        for (JsonElement element : array(value, key, Integer.MAX_VALUE)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(key + " must contain packed block positions");
            }
            result.add(element.getAsLong());
        }
        return List.copyOf(result);
    }

    private static IntentTaskRecord.DecisionSnapshot decodeDecision(JsonObject value) {
        // 还原待答问题；没有任何可选项的问题不允许恢复，否则调用者永远没法回答。
        List<IntentTaskRecord.DecisionOption> options = new ArrayList<>();
        for (JsonElement element : array(value, "options", MAX_OPTIONS)) {
            JsonObject item = element.getAsJsonObject();
            options.add(new IntentTaskRecord.DecisionOption(
                    bounded(text(item, "choice")),
                    bounded(text(item, "description"))));
        }
        if (options.isEmpty()) throw new IllegalArgumentException(
                "persisted semantic decision has no options");
        return new IntentTaskRecord.DecisionSnapshot(
                UUID.fromString(text(value, "id")),
                safeMessage(text(value, "question")),
                options,
                safeElement(value.get("context")).toString());
    }

    private static IntentTaskRecord.DecisionAnswer decodeAnswer(JsonObject value) {
        return new IntentTaskRecord.DecisionAnswer(
                UUID.fromString(text(value, "decision_id")),
                bounded(text(value, "choice")),
                safeElement(value.get("details")).toString());
    }

    private static IntentTaskRecord.TerminalSnapshot decodeTerminal(JsonObject value) {
        // “结束结果”只能写成功、失败、超时或取消，不能把 RUNNING 冒充成已经结束。
        TaskState state = TaskState.valueOf(text(value, "state").toUpperCase());
        if (!state.isTerminal()) throw new IllegalArgumentException(
                "persisted terminal state is not terminal");
        return new IntentTaskRecord.TerminalSnapshot(
                state,
                safeElement(value.get("result")).toString(),
                longValue(value, "game_time", 0L));
    }

    private static JsonArray array(JsonObject root, String key, int maximum) {
        // 缺少列表按空列表兼容；已经存在但类型不对或数量过多，则拒绝整份内容，不静默截取。
        if (!root.has(key)) return new JsonArray();
        if (!root.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        JsonArray array = root.getAsJsonArray(key);
        if (array.size() > maximum) {
            throw new IllegalArgumentException(key + " exceeds its persisted bound");
        }
        return array;
    }

    private static JsonObject worldPosition(Goal.WorldPosition position) {
        JsonObject value = new JsonObject();
        value.addProperty("x", position.x());
        value.addProperty("y", position.y());
        value.addProperty("z", position.z());
        if (position.dimension() != null) {
            value.addProperty("dimension", bounded(position.dimension()));
        }
        return value;
    }

    private static Goal.WorldPosition decodePosition(JsonObject value) {
        return new Goal.WorldPosition(
                integer(value, "x", 0),
                integer(value, "y", 0),
                integer(value, "z", 0),
                value.has("dimension") && value.get("dimension").isJsonPrimitive()
                        ? bounded(value.get("dimension").getAsString()) : null);
    }

    private static JsonObject safeGoal(Goal goal) {
        // 目标里的合法蓝图另按蓝图规则检查，其他内容不能含内部操作字段；若过滤会改掉目标，就拒绝保存。
        JsonObject original = goal.toJson();
        JsonObject inspection = BlueprintGoalData.instructionView(goal);
        JsonElement safe = safeGoalElement(inspection, 0);
        if (!safe.isJsonObject()) {
            throw new IllegalArgumentException("semantic goal must encode as an object");
        }
        if (!safe.equals(inspection)) {
            throw new IllegalArgumentException(
                    "semantic goal contains native execution details or exceeds persistence bounds");
        }
        // 执行步骤另存分组范围，公开 Goal JSON 仍只描述原请求；重启不能丢掉这些约束。
        if (!goal.inheritedProtectionLabels().isEmpty()) {
            JsonArray inherited = new JsonArray();
            for (String label : goal.inheritedProtectionLabels()) {
                if (label.length() > MAX_TEXT) throw new IllegalArgumentException("protection label exceeds persistence bounds");
                inherited.add(label);
            }
            original.add("inherited_protected_labels", inherited);
        }
        // 蓝图可能有许多方块，不套普通元数据每层二百五十六项的限制，但目标整体仍不能超过文件预算。
        if (original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > IntentStateStore.MAX_BYTES)
            throw new IllegalArgumentException("semantic goal exceeds checkpoint byte budget; reference a blueprint resource instead");
        return original;
    }

    private static Goal decodeGoal(JsonObject value) {
        // 还原目标再重新检查，并比较是否丢了字段；保存的数据与还原后的目标必须一致。
        Goal goal = Goal.fromJson(value);
        List<String> inherited = new ArrayList<>();
        for (JsonElement label : array(value, "inherited_protected_labels", MAX_RESULT_DEPTH * 256)) {
            if (!label.isJsonPrimitive() || !label.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("persisted protection label must be a string");
            inherited.add(label.getAsString());
        }
        goal = goal.withInheritedProtection(inherited);
        JsonElement safe = safeGoal(goal);
        if (!safe.equals(value)) {
            throw new IllegalArgumentException(
                    "persisted semantic goal contains native execution details or exceeds bounds");
        }
        return goal;
    }

    private static JsonObject safeObject(String json) {
        // 用于结果／背景的宽松读取：无法解析就改成空对象，不让一条坏诊断文字阻断所有状态保存。
        try {
            JsonElement element = JsonParser.parseString(
                    json == null || json.isBlank() ? "{}" : json);
            JsonElement safe = safeElement(element);
            return safe.isJsonObject() ? safe.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException invalid) {
            return new JsonObject();
        }
    }

    private static JsonElement safeElement(JsonElement value) {
        return safeElement(value, 0);
    }

    private static JsonElement safeElement(JsonElement value, int depth) {
        // 递归处理普通结果：删内部字段，限制深度，每个列表或对象最多二百五十六项，长文字也会缩短。
        if (value == null || value.isJsonNull() || depth > MAX_RESULT_DEPTH) {
            return JsonNull.INSTANCE;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(safeMessage(primitive.getAsString()));
            return primitive.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            int count = 0;
            for (JsonElement element : value.getAsJsonArray()) {
                if (count++ >= 256) break;
                result.add(safeElement(element, depth + 1));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (count++ >= 256) break;
            String key = entry.getKey();
            if (isInternalKey(key)) continue;
            result.add(bounded(key), safeElement(entry.getValue(), depth + 1));
        }
        return result;
    }

    private static boolean isInternalKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (INTERNAL_KEYS.contains(lower)) return true;
        return lower.endsWith("_entity_id") || lower.endsWith("_entity_ids")
                || lower.endsWith("_entity_uuid") || lower.endsWith("_entity_uuids")
                || lower.endsWith("_runtime_id") || lower.endsWith("_runtime_ids")
                || lower.endsWith("_slot") || lower.endsWith("_slots")
                || lower.endsWith("_click") || lower.endsWith("_clicks")
                || lower.endsWith("_route") || lower.endsWith("_waypoints")
                || lower.endsWith("_path_nodes") || lower.endsWith("_receipt")
                || lower.endsWith("_receipts") || lower.endsWith("_position")
                || lower.endsWith("_center") || lower.endsWith("_location")
                || lower.endsWith("_destination") || lower.endsWith("_bounds")
                || lower.endsWith("_x") || lower.endsWith("_y")
                || lower.endsWith("_z");
    }

    /** 目标参数与执行结果用不同的过滤名单；合法的目标坐标、装备部位等可以保留。 */
    private static JsonElement safeGoalElement(JsonElement value, int depth) {
        if (value == null || value.isJsonNull() || depth > MAX_RESULT_DEPTH) {
            return JsonNull.INSTANCE;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(bounded(primitive.getAsString()));
            return primitive.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            int count = 0;
            for (JsonElement element : value.getAsJsonArray()) {
                if (count++ >= 256) break;
                result.add(safeGoalElement(element, depth + 1));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (count++ >= 256) break;
            String key = entry.getKey();
            if (isGoalInternalKey(key)) continue;
            result.add(bounded(key), safeGoalElement(entry.getValue(), depth + 1));
        }
        return result;
    }

    private static boolean isGoalInternalKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (GOAL_INTERNAL_KEYS.contains(lower)) return true;
        return lower.endsWith("_entity_id") || lower.endsWith("_entity_ids")
                || lower.endsWith("_entity_uuid") || lower.endsWith("_entity_uuids")
                || lower.endsWith("_runtime_id") || lower.endsWith("_runtime_ids")
                || lower.endsWith("_click") || lower.endsWith("_clicks")
                || lower.endsWith("_route") || lower.endsWith("_waypoints")
                || lower.endsWith("_path_nodes") || lower.endsWith("_receipt")
                || lower.endsWith("_receipts");
    }

    private static String safeMessage(String value) {
        // 包含异常或内部类名的旧消息被替换成通用提示；当前做法会同时丢掉这条消息里原有的排错细节。
        String message = bounded(value);
        String lower = message.toLowerCase();
        if (lower.contains("exception") || lower.contains("stack trace")
                || lower.contains("internal error") || lower.contains("task start failed")
                || lower.contains("task tick failed") || lower.contains("task result failed")
                || lower.contains("internal tool") || lower.contains("java.")
                || lower.contains("net.minecraft.") || lower.contains("org.maiwithu.")) {
            return "A previous semantic action stopped safely; re-observe current facts before retrying.";
        }
        return message;
    }

    private static String bounded(String value) {
        // 去掉两端空白并限制长度，超出部分直接截去。
        if (value == null) return "";
        String result = value.strip();
        return result.length() <= MAX_TEXT ? result : result.substring(0, MAX_TEXT);
    }

    private static String text(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return bounded(value.get(key).getAsString());
    }

    private static int integer(JsonObject value, String key, int fallback) {
        if (!value.has(key)) return fallback;
        return value.get(key).getAsInt();
    }

    private static long longValue(JsonObject value, String key, long fallback) {
        if (!value.has(key)) return fallback;
        return value.get(key).getAsLong();
    }
}
