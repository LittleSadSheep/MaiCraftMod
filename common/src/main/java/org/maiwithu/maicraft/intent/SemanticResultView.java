package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.api.Internal;
import java.util.Locale;
import java.util.Set;

/** 整理对外任务结果中的文字和内部字段，不改变任务是否成功，也不修改已发生的游戏效果。 */
@Internal
public final class SemanticResultView {
    private static final Set<String> INTERNAL_RESULT_KEYS = Set.of(
            "entity_id", "entity_ids", "requested_entity_ids", "defeated_entity_ids",
            "lost_entity_ids", "unreachable_entity_ids", "combat_by_entity",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot", "slots", "inventory_slots",
            "slot_clicks", "click_sequence", "route", "waypoints", "path_nodes",
            "block_ops", "placements", "cells", "continuation_token",
            "continuation_prefix_hash", "verified_position", "failure_position",
            "final_position", "site_min", "site_max", "remaining_scaffolds",
            "origin", "observed_loaded_bounds", "explored_centers",
            "observed_unloaded_frontier_samples", "failed_legs",
            "x", "y", "z", "position", "center", "location", "destination", "bounds");

    private SemanticResultView() { }

    /** 计划路线、操作槽位和运行时实体编号留在 Mod 内部，不作为下一次动作指令公开。 */
    private static boolean internalKey(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        // 失败格和未拆支撑是已经观察到的诊断事实，保留它们不会开放新的方块操作入口。
        if (key.equals("failure_position") || key.equals("remaining_scaffolds")) return false;
        return INTERNAL_RESULT_KEYS.contains(key)
                || key.endsWith("_cells")
                || key.endsWith("_ops")
                || key.endsWith("_placements")
                || key.endsWith("_receipts")
                || key.endsWith("_routes")
                || key.endsWith("_waypoints")
                || key.endsWith("_path_nodes")
                || key.endsWith("_position")
                || key.endsWith("_center")
                || key.endsWith("_location")
                || key.endsWith("_destination")
                || key.endsWith("_bounds")
                || key.endsWith("_x")
                || key.endsWith("_y")
                || key.endsWith("_z")
                || key.endsWith("_entity_id")
                || key.endsWith("_entity_ids")
                || key.endsWith("_runtime_id")
                || key.endsWith("_runtime_ids");
    }

    /** 移动结果保留到达含义，去掉只属于本次执行的位置和实体编号。 */
    static String message(String raw) {
        if (raw == null) return "";
        return raw
                // 到达目标后的成功说明先保持完整句意，再去掉内部坐标。
                // 若只替换数字，原来的“到达指定格”可能重复叠加位置说明。
                .replaceFirst(
                        "(?i)reached the exact cell -?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\.",
                        "reached the exact target cell.")
                .replaceFirst(
                        "(?i)arrived at location x\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\s+z\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?,"
                                + "\\s*standing on the ground at y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\.",
                        "arrived at the target location, standing on solid ground.")
                .replaceFirst(
                        "(?i)The exact cell y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)? wasn't reachable",
                        "The exact requested cell wasn't reachable")
                .replaceAll("(?i)entity\\s*#?\\s*\\d+", "selected entity")
                .replaceAll("(?i)runtime\\s+id\\s*[:=]?\\s*\\d+", "internal target")
                .replaceAll(
                        "(?i)(?:location\\s+)?x\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\s+z\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?(?:,?\\s*standing\\s+on\\s+the\\s+ground\\s+at\\s+y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?)?",
                        "the internally verified location")
                .replaceAll("(?<!\\d)-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+(?!\\d)",
                        "the internally verified location")
                .replaceAll("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?",
                        "the internally verified coordinate");
    }

    /** 保留成功、超时和中断事实，只转换其对外文字与证据格式。 */
    public static TaskResult result(TaskResult raw) {
        if (raw == null) return TaskResult.fail("internal action failed");
        return new TaskResult(
                raw.success(),
                message(raw.message()),
                raw.timedOut(),
                raw.interrupted(),
                data(raw.data()));
    }

    public static Map<String, Object> data(Map<String, ?> source) {
        // 对外回复前，按字段名删掉内部使用的坐标、路径、槽位等；嵌套的 Map、列表和 JSON 也继续检查。
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || internalKey(key)) continue;
            Object value = sanitizeEntry(key, entry.getValue());
            if (value != null) clean.put(key, value);
        }
        return Map.copyOf(clean);
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof JsonElement json) {
            return jsonValue(json);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (internalKey(key)) continue;
                Object nested = sanitizeEntry(key, entry.getValue());
                if (nested != null) clean.put(key, nested);
            }
            return Map.copyOf(clean);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> clean = new ArrayList<>();
            for (Object element : collection) {
                Object nested = sanitizeValue(element);
                if (nested != null) clean.add(nested);
            }
            return List.copyOf(clean);
        }
        if (value instanceof String text) {
            String stripped = text.strip();
            if ((stripped.startsWith("{") && stripped.endsWith("}"))
                    || (stripped.startsWith("[") && stripped.endsWith("]"))) {
                try {
                    return jsonValue(JsonParser.parseString(stripped));
                } catch (RuntimeException ignored) {
                    // 只是外形像 JSON 的普通说明仍按文字处理，不能因此丢掉任务结果。
                }
            }
            return message(text);
        }
        return value;
    }

    /** JSON 形式的子任务证据同样经过字段整理，供已完成步骤及任务详情复用。 */
    public static Object jsonValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry
                    : value.getAsJsonObject().entrySet()) {
                if (internalKey(entry.getKey())) continue;
                Object nested = sanitizeEntry(entry.getKey(), entry.getValue());
                if (nested != null) clean.put(entry.getKey(), nested);
            }
            return Map.copyOf(clean);
        }
        if (value.isJsonArray()) {
            List<Object> clean = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                Object nested = jsonValue(element);
                if (nested != null) clean.add(nested);
            }
            return List.copyOf(clean);
        }
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        return message(primitive.getAsString());
    }

    private static Object sanitizeEntry(String key, Object value) {
        // 地图蓝图和结构差异都是只读观测，保留真实状态属性与未知格子，不能再按动作字段名删掉其中的数据。
        if (key.equals("as_built_blueprint") || key.equals("blueprint_diff")) return value;
        // 只读失败证据完整保留，不能把嵌套坐标再次过滤成空对象，让调用者反复查询仍无法定位。
        if (key.equals("failure_position") || key.equals("remaining_scaffolds")) return value;
        // 已经实际挖过的方块是供人核查的事实，因此这个字段例外保留位置，最多列出三十二块。
        if (!"confirmed_harvests".equals(key)) return sanitizeValue(value);
        var json = new Gson().toJsonTree(value);
        if (!json.isJsonArray()) return List.of();
        List<Object> result = new ArrayList<>();
        for (var entry : json.getAsJsonArray()) {
            if (result.size() == 32) break;
            if (!entry.isJsonObject()) continue;
            var row = entry.getAsJsonObject();
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String field : List.of("block_id", "block_state", "natural_tree_filter_enabled")) {
                if (row.has(field)) clean.put(field, jsonValue(row.get(field)));
            }
            if (row.has("position") && row.get("position").isJsonObject()) {
                var pos = row.getAsJsonObject("position");
                Map<String, Integer> coordinates = new LinkedHashMap<>();
                for (String axis : List.of("x", "y", "z")) {
                    var number = pos.get(axis);
                    if (number != null && number.isJsonPrimitive() && number.getAsJsonPrimitive().isNumber()
                            && number.getAsDouble() == number.getAsInt()) coordinates.put(axis, number.getAsInt());
                }
                if (coordinates.size() == 3) clean.put("position", Map.copyOf(coordinates));
            }
            result.add(clean);
        }
        return List.copyOf(result);
    }
}
