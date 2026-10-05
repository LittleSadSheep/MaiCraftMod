package org.maiwithu.maicraft.task;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Collection;

/**
 * 一次执行结束后的答复：做成没有、为什么停下、已经发生了什么。
 * 例如“走不到”“走太久超时”“被玩家叫停”都没完成移动，但原因不同，要分开告诉调用者。
 * 这是内部结果；总任务对外回复前，还可能删掉路线、物品槽号等内部操作细节。
 *
 * @param success 当前执行流程是否成功结束；组合目标明确跳过的步骤还会在结果中单独标明。
 * @param message 给人读的简短说明，例如“路线走完了，但还没到目标”。
 * @param timedOut 是否因为超过执行时间而结束。
 * @param interrupted 是否被叫停，例如玩家取消任务。
 * @param data 这件事的具体结果，例如挖了多少块；没有附加信息时使用空 Map。
 * @param cancelSource 取消来源；只有 interrupted 为真时才可能有值，区分新任务接管、操作者取消等入口。
 */
public record TaskResult(boolean success,
                         String message,
                         boolean timedOut,
                         boolean interrupted,
                         Map<String, Object> data,
                         String cancelSource) {

    private static final Gson GSON = new Gson();

    /** 旧的三参布尔形态继续可用；这些调用点都不掌握取消来源，置为未知。 */
    public TaskResult(boolean success, String message, boolean timedOut,
                      boolean interrupted, Map<String, Object> data) {
        this(success, message, timedOut, interrupted, data, null);
    }

    /** 下面几组方法分别创建成功、失败、超时和取消的答复，避免调用者自己拼布尔值。 */
    public static TaskResult ok(String message, Map<String, Object> data) {
        return new TaskResult(true, message, false, false, data);
    }

    public static TaskResult ok(String message) {
        return new TaskResult(true, message, false, false, Map.of());
    }

    public static TaskResult fail(String message, Map<String, Object> data) {
        return new TaskResult(false, message, false, false, data);
    }

    public static TaskResult fail(String message) {
        return new TaskResult(false, message, false, false, Map.of());
    }

    public static TaskResult timeout(String message) {
        return new TaskResult(false, message, true, false, Map.of());
    }

    public static TaskResult cancelled(String message) {
        return new TaskResult(false, message, false, true, Map.of());
    }

    public static TaskResult cancelled(String message, String cancelSource) {
        return new TaskResult(false, message, false, true, Map.of(), cancelSource);
    }

    /** 只替换附加结果，保留成败与取消来源；包装回执时不许把这些事实弄丢。 */
    public TaskResult withData(Map<String, Object> data) {
        return new TaskResult(success, message, timedOut, interrupted, data, cancelSource);
    }

    /** 替换取消来源；只在子任务带回更具体来源（如命令未发出即取消）时由结果组装方调用。 */
    public TaskResult withCancelSource(String cancelSource) {
        return new TaskResult(success, message, timedOut, interrupted, data, cancelSource);
    }

    /** 转成工具回复使用的 JSON 文本；未发生超时或取消时省略对应字段，没有附加结果时省略 data。 */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("message", message == null ? "" : message);
        if (timedOut) root.addProperty("timed_out", true);
        if (interrupted) root.addProperty("interrupted", true);
        if (cancelSource != null && !cancelSource.isBlank()) root.addProperty("cancel_source", cancelSource);
        if (data != null && !data.isEmpty()) {
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) dataObj.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) dataObj.addProperty(e.getKey(), b);
                // 整机差异和现场蓝图保留 JSON 结构，让模型能按字段直读实际方块，避免先翻整段转义文本。
                else if (v instanceof JsonElement json) dataObj.add(e.getKey(), json.deepCopy());
                // 列表和 Map 保留为 JSON 数组或对象，数字和布尔值也保留类型；其他值转成字符串。
                else if (v instanceof Collection<?> || v instanceof Map<?, ?>) {
                    dataObj.add(e.getKey(), GSON.toJsonTree(v));
                } else if (v != null) dataObj.addProperty(e.getKey(), v.toString());
            }
            root.add("data", dataObj);
        }
        return root.toString();
    }
}
