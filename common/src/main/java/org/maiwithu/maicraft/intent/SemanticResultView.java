package org.maiwithu.maicraft.intent;

import java.util.Locale;
import java.util.Set;

/** 整理对外任务结果中的文字和内部字段，不改变任务是否成功，也不修改已发生的游戏效果。 */
final class SemanticResultView {
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
    static boolean internalKey(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
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
}
