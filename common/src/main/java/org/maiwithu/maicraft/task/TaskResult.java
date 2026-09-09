package org.maiwithu.maicraft.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 一次执行结束后的答复：做成没有、为什么停下、已经发生了什么。
 * 例如“走不到”“走太久超时”“被玩家叫停”都没完成移动，但原因不同，要分开告诉调用者。
 * 这是内部结果；总任务对外回复前，还可能删掉路线、物品槽号等内部操作细节。
 *
 * @param success 是否达到目标；没超时、没取消，也仍然可能失败。
 * @param message 给人读的简短说明，例如“路线走完了，但还没到目标”。
 * @param timedOut 是否因为超过执行时间而结束。
 * @param interrupted 是否被叫停，例如玩家取消任务。
 * @param data 这件事的具体结果，例如挖了多少块；没有附加信息时使用空 Map。
 */
public record TaskResult(boolean success,
                         String message,
                         boolean timedOut,
                         boolean interrupted,
                         Map<String, Object> data) {

    private static final Gson GSON = new Gson();

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

    /** 转成工具回复使用的 JSON 文本；未发生超时或取消时省略对应字段，没有附加结果时省略 data。 */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("message", message == null ? "" : message);
        if (timedOut) root.addProperty("timed_out", true);
        if (interrupted) root.addProperty("interrupted", true);
        if (data != null && !data.isEmpty()) {
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) dataObj.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) dataObj.addProperty(e.getKey(), b);
                // 列表和 Map 保留为 JSON 数组或对象，数字和布尔值也保留类型；其他值转成字符串。
                else if (v instanceof java.util.Collection<?> || v instanceof Map<?, ?>) {
                    dataObj.add(e.getKey(), GSON.toJsonTree(v));
                } else if (v != null) dataObj.addProperty(e.getKey(), v.toString());
            }
            root.add("data", dataObj);
        }
        return root.toString();
    }
}
