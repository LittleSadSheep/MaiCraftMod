// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 记录无线查询和针对缺料的库存观察；未派发取物不等于已证明网络为空。 */
final class AcquisitionWirelessEvidence {
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private Map<String, Object> query = Map.of();
    private Boolean carriedTerminalAvailable;
    private int checkedNeeds;

    void access(boolean available) { carriedTerminalAvailable = available; }

    void queryStarted(long tick) {
        // 查询只打开终端核对库存，必须与真正提取材料的尝试分开记录。
        query = Map.of("status", "running", "started_game_tick", tick);
    }

    void querySettled(TaskState state, TaskResult result, long started, long finished) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("status", state == TaskState.SUCCESS && result != null && result.success() ? "succeeded" : "failed");
        facts.put("terminal_state", state.name().toLowerCase(Locale.ROOT));
        facts.put("started_game_tick", started); facts.put("finished_game_tick", finished);
        // 保留真实连接或界面收尾失败原因，不能把失败查询解释成查到零件数为零。
        if (result != null) {
            facts.put("message", result.message() == null ? "" : result.message());
            if (result.data() != null) for (String key : List.of("failure_code", "cause_code", "outcome_uncertain", "terminal_access")) {
                Object value = result.data().get(key);
                if (value instanceof String || value instanceof Boolean) facts.put(key, value);
            }
        }
        query = Map.copyOf(facts);
    }

    void checked(AcquisitionNeed need, Optional<StockEvidence.Snapshot> stock, long tick) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("depth", need.depth); facts.put("checked_game_tick", tick);
        facts.put("item_ids", need.itemIds.stream().limit(16).map(Object::toString).toList());
        facts.put("acceptable_item_count", need.itemIds.size());
        facts.put("item_ids_truncated", need.itemIds.size() > 16);
        facts.put("status", stock.isPresent() ? "observed" : "unknown");
        // 只报告当前需求全体候选的合计提示；未知不填零，已提取物品可能已从这份缓存里扣减。
        stock.ifPresent(snapshot -> {
            long available = 0;
            for (var item : need.itemIds) {
                long count = Math.max(0L, snapshot.storedCount(item));
                available = count > Long.MAX_VALUE - available ? Long.MAX_VALUE : available + count;
            }
            facts.put("matching_count", available); facts.put("observed_game_tick", snapshot.observedGameTick());
            facts.put("source", snapshot.source().name().toLowerCase(Locale.ROOT));
        });
        checkedNeeds++;
        if (checks.size() == 8) checks.removeFirst();
        checks.add(Map.copyOf(facts));
    }

    Map<String, Object> describe() {
        if (carriedTerminalAvailable == null && query.isEmpty() && checks.isEmpty()) return Map.of();
        var facts = new LinkedHashMap<String, Object>();
        if (carriedTerminalAvailable != null) facts.put("carried_terminal_available", carriedTerminalAvailable);
        facts.put("last_query", query.isEmpty() ? Map.of("status", "not_started") : query);
        facts.put("need_checks", List.copyOf(checks)); facts.put("need_checks_total", checkedNeeds);
        facts.put("need_checks_omitted", checkedNeeds - checks.size());
        facts.put("scope", "Latest wireless query and recent requested-material checks; counts are cached planning hints adjusted for carried inventory changes, not a fresh whole-network emptiness proof or extraction receipt.");
        return Map.copyOf(facts);
    }
}
