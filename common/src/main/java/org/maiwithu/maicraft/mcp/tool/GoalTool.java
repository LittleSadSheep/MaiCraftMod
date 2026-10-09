// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;

import java.util.List;
import java.util.Objects;

/**
 * goal：查看、暂停、解除暂停、取消下达过的目标，回答目标提出的问题；goal_id 是 execute 返回的编号。
 * 暂停、解除暂停、取消、回答之后都返回目标此刻的样子，不用再查一次。
 */
public final class GoalTool implements McpTool {
    private static final List<String> OPERATIONS = List.of("get", "list", "pause", "resume", "cancel", "answer");

    private final GoalRunTable table;
    private final ClientThread clientThread;

    public GoalTool(GoalRunTable table, ClientThread clientThread) {
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    @Override public String name() {
        return ToolCatalog.GOAL;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("operation", "goal_id", "answer"));
        String operation = check.choice(arguments, "operation", "operation", OPERATIONS);
        if (operation == null && !arguments.has("operation")) {
            check.error("operation", "缺少 operation", String.join(" / ", OPERATIONS));
        }
        Integer goalId = check.integer(arguments, "goal_id", "goal_id", operation != null && !operation.equals("list"));
        String answer = check.text(arguments, "answer", "answer", "answer".equals(operation));
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        JsonObject data = clientThread.call(context -> switch (operation) {
            case "list" -> list();
            case "pause" -> {
                table.pause(goalId);
                yield view(goalId);
            }
            case "resume" -> {
                table.resume(goalId);
                yield view(goalId);
            }
            case "cancel" -> {
                table.cancel(goalId);
                yield view(goalId);
            }
            case "answer" -> {
                table.answer(goalId, answer);
                yield view(goalId);
            }
            default -> view(goalId);
        });
        return ToolReply.ok(data, check.notes(), null);
    }

    /** 目标此刻的样子：处境、在等的问题、在做什么、结束后的完整结果。 */
    private JsonObject view(long id) {
        GoalRun run = table.find(id).orElseThrow(() -> new GoalRunTable.UnknownGoalRun(id));
        return ResultJson.goalRun(run, table.pendingQuestion(id).orElse(null), table.doing(id).orElse(null));
    }

    /** 最近下达的目标，一项一行：编号、能力、处境、结束了的一句话结论。 */
    private JsonObject list() {
        JsonArray goals = new JsonArray();
        for (GoalRun run : table.recent()) {
            JsonObject item = new JsonObject();
            item.addProperty("goal_id", run.id());
            item.addProperty("ability", run.goal().ability());
            if (run.goal().purpose() != null) item.addProperty("purpose", run.goal().purpose());
            item.addProperty("state", ResultJson.lower(run.state()));
            if (run.result() != null) {
                item.addProperty("status", ResultJson.lower(run.result().status()));
                item.addProperty("summary", run.result().summary());
            }
            goals.add(item);
        }
        JsonObject data = new JsonObject();
        data.add("goals", goals);
        return data;
    }
}
