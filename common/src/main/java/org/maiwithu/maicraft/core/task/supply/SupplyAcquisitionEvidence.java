// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 施工补料结束后保留近期获取和配方分支事实，让规划者区分缺货、失败尝试与已经发生的取物。 */
final class SupplyAcquisitionEvidence {
    private static final int ROW_LIMIT = 8, ALTERNATIVE_LIMIT = 16;

    private SupplyAcquisitionEvidence() {}

    static void append(Map<String, Object> child, Map<String, Object> receipt) {
        // 施工补料同样完整交付每只箱子的观察、记忆更新与未确认项，不让后面的调查被历史摘要遮掉。
        if (child.containsKey("container_search_scope")) receipt.put("container_search_scope", child.get("container_search_scope"));
        var containers = new ArrayList<Map<String, Object>>();
        if (child.get("attempts") instanceof List<?> visits) for (Object value : visits) {
            if (!(value instanceof Map<?, ?> row) || !(row.get("child_data") instanceof Map<?, ?> data)
                    || !("manage_container".equals(row.get("child_tool")) || data.containsKey("container_observation"))) continue;
            var visit = new LinkedHashMap<String, Object>();
            copy(row, visit, "source", "terminal_state", "child_success", "child_message", "inventory_progress");
            visit.put("container_result", data); containers.add(Map.copyOf(visit));
        }
        if (!containers.isEmpty()) receipt.put("container_investigations", List.copyOf(containers));
        // 子任务自己的历史可能已经有限额；这里的计数只描述收到的记录，不能宣称覆盖了全部实际尝试。
        for (String field : List.of("attempts", "recipe_trace")) {
            if (!(child.get(field) instanceof List<?> rows)) continue;
            var summaries = new ArrayList<Map<String, Object>>();
            int start = Math.max(0, rows.size() - ROW_LIMIT);
            for (Object value : rows.subList(start, rows.size())) {
                if (!(value instanceof Map<?, ?> row)) continue;
                summaries.add(field.equals("attempts") ? attempt(row) : recipe(row));
            }
            receipt.put(field, List.copyOf(summaries));
            receipt.put(field + "_reported_count", rows.size());
            receipt.put(field + "_omitted_reported_rows", rows.size() - summaries.size());
        }
        receipt.put("acquisition_evidence_scope",
                "Selected facts from the last reported acquisition rows; upstream history may already be bounded. "
                        + "Reported counts are not total execution counts. Internal child payloads and full preparation lists are omitted.");
    }

    private static Map<String, Object> attempt(Map<?, ?> row) {
        var result = new LinkedHashMap<String, Object>();
        copy(row, result, "source", "detail", "child_tool", "terminal_state", "inventory_before", "inventory_after",
                "inventory_progress", "effects_observed", "stopped_because_final_fact_satisfied", "child_success", "child_message");
        // 子动作只留下失败和不确定性事实；整张无线网络清单、菜单槽位和内部动作不能再灌回父任务。
        if (row.get("child_data") instanceof Map<?, ?> data) {
            var outcome = new LinkedHashMap<String, Object>();
            copy(data, outcome, "failure_type", "failure_code", "outcome_uncertain", "world_change_uncertain", "requires_decision");
            if (!outcome.isEmpty()) result.put("child_outcome", Map.copyOf(outcome));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> recipe(Map<?, ?> row) {
        var result = new LinkedHashMap<String, Object>();
        copy(row, result, "recipe_id", "output_item_id", "depth", "missing_count", "observed_stock_material_hint",
                "internal_prerequisite", "alternative_recipe_ids", "alternative_output_item_ids", "missing_item_ids",
                "prerequisite_item_ids", "allowed_sources");
        if (row.get("preparation_plan") instanceof Map<?, ?> preparation) {
            var estimate = new LinkedHashMap<String, Object>();
            // 预算耗尽仍必须可见，避免把启发式选中的材料说成游戏唯一接受的原料。
            copy(preparation, estimate, "feasible", "search_complete", "estimated_cost");
            result.put("preparation_plan", Map.copyOf(estimate));
        }
        return Map.copyOf(result);
    }

    private static void copy(Map<?, ?> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof List<?> values) {
                // 宽标签只显示有界候选前缀，并同时公开省略数量，不能让模型把前缀误认作完整接受集。
                target.put(key, List.copyOf(values.subList(0, Math.min(ALTERNATIVE_LIMIT, values.size()))));
                target.put(key + "_reported_count", values.size());
                target.put(key + "_omitted_count", Math.max(0, values.size() - ALTERNATIVE_LIMIT));
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                target.put(key, value);
            }
        }
    }
}
