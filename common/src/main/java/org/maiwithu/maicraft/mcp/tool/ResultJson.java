// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.List;
import java.util.Locale;

/**
 * 把内核里的任务结果、目标运行、问题和任务事件写成 LLM 看到的 JSON。
 *
 * <p>只换写法，不删事实：结果里的每一条变化、没能确认的交互、试过的办法都原样交出去。
 * 能力特有的结果细节是各能力自己的 record，字段名按下划线写法（{@code bedSource} → {@code bed_source}），
 * 枚举取值写成小写（{@code CRAFTED} → {@code crafted}）。
 */
public final class ResultJson {
    private static final Gson DETAILS = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeHierarchyAdapter(Enum.class,
                    (JsonSerializer<Enum<?>>) (value, type, context) -> new JsonPrimitive(lower(value)))
            .disableHtmlEscaping()
            .create();

    private ResultJson() {}

    /** 任务结果：status、summary、changes、remaining、problem、unconfirmed、attempts、details。 */
    public static JsonObject result(TaskResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("status", lower(result.status()));
        json.addProperty("summary", result.summary());
        json.add("changes", changes(result.changes()));
        json.add("remaining", texts(result.remaining()));
        if (result.problem() != null) {
            json.add("problem", problem(result.problem()));
        }
        json.add("unconfirmed", changes(result.unconfirmed()));
        JsonArray attempts = new JsonArray();
        for (Attempt attempt : result.attempts()) {
            JsonObject item = new JsonObject();
            item.addProperty("tried", attempt.tried());
            item.addProperty("result", attempt.result());
            attempts.add(item);
        }
        json.add("attempts", attempts);
        if (result.details() != ResultDetails.NONE) {
            json.add("details", DETAILS.toJsonTree(result.details()));
        }
        return json;
    }

    /**
     * 目标运行：LLM 用 task 查到的样子。
     *
     * @param pending 此刻在等的问题（sequence 时是正在跑的那一步的问题）；没有时为 null
     * @param doing   此刻在做什么的一句话；已经结束时为 null
     */
    public static JsonObject goalRun(GoalRun run, Question pending, String doing) {
        JsonObject json = new JsonObject();
        json.addProperty("task_id", run.id());
        json.addProperty("ability", run.goal().ability());
        if (run.goal().purpose() != null) {
            json.addProperty("purpose", run.goal().purpose());
        }
        json.addProperty("state", lower(run.state()));
        if (!run.goal().steps().isEmpty()) {
            JsonObject step = new JsonObject();
            step.addProperty("index", run.stepIndex());
            step.addProperty("total", run.goal().steps().size());
            json.add("step", step);
        }
        if (pending != null) {
            json.add("question", question(pending));
        }
        if (doing != null && run.state() != GoalRunState.FINISHED) {
            json.addProperty("doing", doing);
        }
        if (run.result() != null) {
            json.add("result", result(run.result()));
        }
        return json;
    }

    /** 问题：为什么问、问什么、可选的回答（回答时交回选项的 id）。 */
    public static JsonObject question(Question question) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", lower(question.reason()));
        json.addProperty("text", question.text());
        JsonArray options = new JsonArray();
        for (Question.Option option : question.options()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", option.id());
            item.addProperty("meaning", option.meaning());
            options.add(item);
        }
        json.add("options", options);
        return json;
    }

    /** 任务事件：序号、发生了什么、哪个目标、一句话，结束时附结果状态。 */
    public static JsonObject event(TaskEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("cursor", event.cursor());
        json.addProperty("kind", lower(event.kind()));
        // 与目标无关的事件不带 task_id；死亡恢复决策的编号是负数（-2 起），也是一条能按编号回答的记录，照样带上。
        if (event.goalRunId() != TaskEventLog.NO_GOAL) {
            json.addProperty("task_id", event.goalRunId());
        }
        json.addProperty("message", event.message());
        if (event.status() != null) {
            json.addProperty("status", lower(event.status()));
        }
        return json;
    }

    private static JsonObject problem(Problem problem) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", problem.kind().name());
        json.addProperty("message", problem.message());
        if (problem.suggestion() != null) {
            json.addProperty("suggestion", problem.suggestion());
        }
        return json;
    }

    private static JsonArray changes(List<Change> changes) {
        JsonArray array = new JsonArray();
        for (Change change : changes) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", lower(change.kind()));
            item.addProperty("what", change.what());
            item.addProperty("count", change.count());
            if (change.note() != null) {
                item.addProperty("note", change.note());
            }
            array.add(item);
        }
        return array;
    }

    private static JsonElement texts(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
