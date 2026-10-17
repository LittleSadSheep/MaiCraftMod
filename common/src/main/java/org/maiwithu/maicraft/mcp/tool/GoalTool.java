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
 *
 * <p>结束了的目标默认只给结论、问题和小块事实，大块证据（长的试过的办法、细节里大的那几项、长列表）折起来，
 * 路径与大小列在 result.folded；get 带 path 读回那一段，列表与对象再带 offset / limit 翻页。
 * 两三条短的试过的办法照原样留着，没做成时一眼看到为什么。
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
        check.rejectUnknownFields(arguments, "", List.of("operation", "goal_id", "answer", "path", "offset", "limit"));
        String operation = check.choice(arguments, "operation", "operation", OPERATIONS);
        if (operation == null && !arguments.has("operation")) {
            check.error("operation", "缺少 operation", String.join(" / ", OPERATIONS));
        }
        Integer goalId = check.integer(arguments, "goal_id", "goal_id", operation != null && !operation.equals("list"));
        String answer = check.text(arguments, "answer", "answer", "answer".equals(operation));
        String path = check.text(arguments, "path", "path", false);
        Integer offset = check.integer(arguments, "offset", "offset", false);
        Integer limit = check.integer(arguments, "limit", "limit", false);
        checkPaging(check, operation, path, offset, limit);
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        if (path != null) {
            return readPath(goalId, path, offset, limit, check.notes());
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

    /** 目标此刻的样子：处境、在等的问题、在做什么、结束后的结果（大块证据折起来，按路径读）。 */
    private JsonObject view(long id) {
        GoalRun run = table.find(id).orElseThrow(() -> new GoalRunTable.UnknownGoalRun(id));
        return ResultJson.goalRunFolded(run, table.pendingQuestion(id).orElse(null), table.doing(id).orElse(null));
    }

    // path 只配 get，offset / limit 只配 path；offset 不能是负数，limit 在 1 到一页最多的项数之间。
    private static void checkPaging(RequestCheck check, String operation, String path, Integer offset, Integer limit) {
        if (path != null && operation != null && !operation.equals("get")) {
            check.error("path", "path 只在 operation=get 时用", "读结束了的目标里折起来的一段");
        }
        if (path == null && (offset != null || limit != null)) {
            check.error(offset != null ? "offset" : "limit", "offset、limit 只和 path 一起用", "先给 path");
        }
        if (offset != null && offset < 0) check.error("offset", "offset 不能是负数", "0 或上一页给的 next_offset");
        if (limit != null && (limit < 1 || limit > FoldedEvidence.MAX_LIMIT)) {
            check.error("limit", "limit 要在 1 到 " + FoldedEvidence.MAX_LIMIT + " 之间", "不给时按一页的字数装");
        }
    }

    /**
     * 按路径读结束了的目标结果里的一段；列表与对象翻页，还有下一页时 next 照抄就是下一页。
     * 目标还没结束、路径不对都按参数问题回，说清结果里有哪些段。
     */
    private JsonObject readPath(long id, String path, Integer offset, Integer limit, List<String> notes) {
        // 结果在客户端线程上写成 JSON（目标运行表只在那条线程上动），按路径找与翻页在这里做。
        JsonObject result = clientThread.call(context -> {
            GoalRun run = table.find(id).orElseThrow(() -> new GoalRunTable.UnknownGoalRun(id));
            return run.result() == null ? null : ResultJson.result(run.result());
        });
        if (result == null) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "目标 " + id + " 还没结束，没有结果可按路径读",
                    List.of(new FieldError("path", "目标结束后才有结果", "先 goal(get) 看它此刻的样子")), null);
        }
        JsonObject page;
        try {
            page = FoldedEvidence.read(result, path, offset, limit);
        } catch (IllegalArgumentException wrong) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, wrong.getMessage(),
                    List.of(new FieldError("path", wrong.getMessage(), "照抄 result.folded 里的 path")), null);
        }
        JsonObject data = new JsonObject();
        data.addProperty("goal_id", id);
        page.entrySet().forEach(entry -> data.add(entry.getKey(), entry.getValue()));
        JsonObject next = null;
        if (page.has("next_offset")) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("operation", "get");
            arguments.addProperty("goal_id", id);
            arguments.addProperty("path", path);
            arguments.add("offset", page.get("next_offset"));
            if (limit != null) arguments.addProperty("limit", limit);
            next = ToolReply.next(ToolCatalog.GOAL, arguments);
        }
        return ToolReply.ok(data, notes, next);
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
