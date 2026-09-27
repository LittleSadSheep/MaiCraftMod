// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** 分页观察保留各自原生时刻；库存快照不能被当作已经生产物品的证据。 */
final class MachineObservationPages {
    static final int MAX_CHARS = 98_304;
    final JsonArray pages = new JsonArray();
    final Set<String> incomplete = new LinkedHashSet<>();
    private int chars;

    boolean append(JsonObject page, String requestId, int blockIndex) {
        if (!page.has("schema") || !page.get("schema").getAsString().equals("maicraft.machine_snapshot.v1"))
            throw new IllegalArgumentException("unexpected server machine snapshot schema");
        // 先去掉重复展示再计算报告预算；非空物品、组件、原生时刻和未读标记仍按原生事实保留。
        JsonObject frozen = MachineObservationPresentation.compact(page);
        frozen.addProperty("request_id", requestId);
        // 原生位置已由调用方逐项核对；公开回执隐藏坐标后仍可按结构索引分清不同置物台和机械手。
        if (blockIndex >= 0) frozen.addProperty("block_index", blockIndex);
        int size = frozen.toString().length();
        if (pages.size() >= 64 || chars + size > MAX_CHARS) {
            incomplete.add("report_budget_exhausted");
            return false;
        }
        chars += size;
        pages.add(frozen);
        if (!page.has("complete") || !page.get("complete").getAsBoolean()) incomplete.add("native_observation_incomplete");
        return true;
    }

    JsonObject report(int selected, int observed, int nextComponent, long anchorTick) {
        if (pages.isEmpty()) incomplete.add("no_native_observation_returned");
        JsonObject result = new JsonObject();
        result.addProperty("state", incomplete.isEmpty() ? "observed" : "partial");
        result.addProperty("provenance", pages.isEmpty() ? "no_server_observation" : "server_native");
        result.addProperty("complete", incomplete.isEmpty());
        result.addProperty("structural_anchor_tick", anchorTick);
        result.addProperty("atomic_snapshot", false);
        result.addProperty("scope", "loaded components within the original snapshot cube and 16 blocks of the player");
        result.addProperty("selected_components", selected);
        result.addProperty("observed_components", observed);
        result.addProperty("next_component_index", nextComponent);
        result.addProperty("flow_verified", false);
        result.addProperty("production_verified", false);
        result.addProperty("resource_scope", "pages and sided views may alias; do not sum overlapping resources");
        result.addProperty("component_reference", "Each page.block_index refers to relative_blocks in this same inspection snapshot; absent index means the marked center has no listed block.");
        JsonArray gaps = new JsonArray();
        incomplete.forEach(gaps::add);
        result.add("incomplete_reasons", gaps);
        result.add("pages", pages.deepCopy());
        // 把工件及装配进度放到可直接读取的证据索引，避免查看一件物品先遍历几十条空接口。
        result.add("occupied_resource_views", MachineObservationPresentation.occupiedResources(pages));
        result.addProperty("occupied_resource_views_meaning", "Known positive resource views only, with exact identity/components. amount_per_view is not a total; observed_views counts repeated views, not items. source_page indexes pages in this report. Incomplete or omitted pages remain unknown.");
        return result;
    }
}
