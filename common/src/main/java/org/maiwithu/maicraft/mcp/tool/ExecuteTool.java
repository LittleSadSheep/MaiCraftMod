// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * execute：开始做一件事。不是 Minecraft 的 /execute 命令。
 *
 * <p>控制角色的目标交给目标运行表，成为角色的主任务，原来的主任务被替换；马上返回目标编号，
 * 进展用 events 等、用 goal 查。只读分析、只改记忆的目标当场做完，结果直接返回，不打断手上的活。
 *
 * <p>{@code dry_run=true} 只检查目标、不动手，返回一个计划编号；之后用 {@code plan_id} 执行同一个目标。
 */
public final class ExecuteTool implements McpTool {
    /** 检查过的计划最多留几份；LLM 一般检查完马上执行，留太多没有意义。 */
    static final int KEPT_PLANS = 32;

    private final AbilityRegistry registry;
    private final GoalReader reader;
    private final GoalRunTable table;
    private final ClientThread clientThread;
    private final Map<String, Goal> plans = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Goal> eldest) {
            return size() > KEPT_PLANS;
        }
    };

    public ExecuteTool(AbilityRegistry registry, GoalRunTable table, ClientThread clientThread) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reader = new GoalReader(registry);
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    @Override public String name() {
        return ToolCatalog.EXECUTE;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("goal", "dry_run", "plan_id", "request_key"));
        Boolean dryRun = check.bool(arguments, "dry_run", "dry_run");
        String planId = check.text(arguments, "plan_id", "plan_id", false);
        String requestKey = check.text(arguments, "request_key", "request_key", false);
        boolean hasGoal = arguments.has("goal") && !arguments.get("goal").isJsonNull();
        if (hasGoal == (planId != null)) {
            check.error(hasGoal ? "plan_id" : "goal", "goal 和 plan_id 要给一个，且只给一个", "新目标给 goal；执行检查过的计划给 plan_id");
        }
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        if (planId != null) {
            Goal planned = takePlan(planId);
            if (planned == null) {
                return ToolReply.error(ErrorCode.UNKNOWN_ID, "没有编号为 " + planId + " 的计划，或者已经执行过；重新 dry_run 一次");
            }
            return start(planned, requestKey, check.notes());
        }
        GoalReader.Reading reading = reader.read(arguments.get("goal"));
        List<String> notes = concat(check.notes(), reading.notes());
        if (!reading.ok()) {
            return ToolReply.error(reading.code(), "目标有 " + reading.errors().size() + " 处问题", reading.errors(), null);
        }
        if (Boolean.TRUE.equals(dryRun)) {
            JsonObject data = new JsonObject();
            data.addProperty("plan_id", keepPlan(reading.goal()));
            data.addProperty("ability", reading.goal().ability());
            data.addProperty("checked", "目标的写法、参数、目标对象与许可都没有问题");
            return ToolReply.ok(data, notes, null);
        }
        return start(reading.goal(), requestKey, notes);
    }

    private JsonObject start(Goal goal, String requestKey, List<String> notes) {
        ExecutionMode mode = registry.find(goal.ability()).orElseThrow().spec().mode();
        if (mode != ExecutionMode.CONTROLS_PLAYER) {
            // 只读分析、只改记忆：当场做完，不交给控制循环，手上的主任务照常进行。
            GoalRun run = clientThread.call(context -> table.runAside(goal, context));
            return ToolReply.ok(ResultJson.goalRun(run, null, null), notes, null);
        }
        String key = requestKey == null ? UUID.randomUUID().toString() : requestKey;
        GoalRunTable.Launch launch = clientThread.call(context -> table.launch(goal, key));
        GoalRun run = launch.runner().run();
        JsonObject data = new JsonObject();
        data.addProperty("goal_id", run.id());
        data.addProperty("ability", goal.ability());
        data.addProperty("state", ResultJson.lower(run.state()));
        if (launch.repeated()) {
            data.addProperty("repeated", true);
        }
        JsonObject wait = new JsonObject();
        wait.addProperty("goal_id", run.id());
        wait.addProperty("wait_ms", EventsTool.DEFAULT_WAIT_MS);
        return ToolReply.ok(data, notes, ToolReply.next(ToolCatalog.EVENTS, wait));
    }

    private synchronized String keepPlan(Goal goal) {
        String id = "p" + UUID.randomUUID().toString().substring(0, 8);
        plans.put(id, goal);
        return id;
    }

    /** 计划只能执行一次：执行后从表里拿掉，重复执行要重新检查。 */
    private synchronized Goal takePlan(String id) {
        return plans.remove(id);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
