package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.task.TaskResult;

/** 对外结果保留已经破坏的方块位置，隐藏计划中的操作细节，并保留原来的成败与超时事实。 */
public final class HarvestEvidenceContractTest {
    public static void main(String[] args) {
        // 不同子任务会用 Map、JSON 对象或 JSON 文本交回证据，三种形式都要保留真实采掘事实。
        var position = Map.of("x", -71, "y", 106, "z", -4);
        var harvest = Map.of("position", position, "block_id", "minecraft:spruce_log",
                "block_state", "Block{minecraft:spruce_log}[axis=y]", "natural_tree_filter_enabled", false,
                "slot", 3, "route", List.of(position));
        Map<String, Object> child = Map.of("confirmed_harvests", List.of(harvest), "position", position,
                "route", List.of(position), "slot", 3);
        Gson gson = new Gson();
        for (Object encoded : List.of(child, gson.toJsonTree(child), gson.toJson(child))) {
            Object clean = SemanticResultView.data(Map.of("attempts", List.of(Map.of("child_data", encoded))));
            var data = gson.toJsonTree(clean).getAsJsonObject().getAsJsonArray("attempts")
                    .get(0).getAsJsonObject().getAsJsonObject("child_data");
            var row = data.getAsJsonArray("confirmed_harvests").get(0).getAsJsonObject();
            check(row.getAsJsonObject("position").get("y").getAsInt() == 106,
                    "不同层级的结果编码都应保留实际受损方块位置");
            check(row.get("block_state").getAsString().endsWith("[axis=y]"), "保留原方块状态供核查");
            check(!row.has("slot") && !row.has("route") && !data.has("position")
                    && !data.has("slot") && !data.has("route"), "观察证据不能夹带可重放的内部动作计划");
        }
        var bad = Map.of("confirmed_harvests", List.of(Map.of("position", Map.of("x", 0.5, "y", 2, "z", 3))));
        var clean = gson.toJsonTree(SemanticResultView.data(bad)).getAsJsonObject();
        check(!clean.getAsJsonArray("confirmed_harvests").get(0).getAsJsonObject().has("position"),
                "无效坐标不能被转换成虚构的受损方块位置");
        // 整理显示内容不能把超时改成成功，也不能改写子任务随后还要使用的原始证据。
        var raw = new TaskResult(false, "reached the exact cell -71,106,-4.", true, false, child);
        var projected = SemanticResultView.result(raw);
        check(!projected.success() && projected.timedOut() && !projected.interrupted(), "结果投影应保留原有终态");
        check(projected.message().equals("reached the exact target cell."), "到达说明应保留完整句意");
        check(child.containsKey("route") && child.containsKey("position"), "投影不能修改原始任务证据");
        System.out.println("HarvestEvidenceContractTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
