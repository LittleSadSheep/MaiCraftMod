// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;

/** 先列全能力名，再按需读用途或直接 focus 契约，避免选动作前反复翻页。 */
final class AbilitySearch {
    private AbilitySearch() {}

    static JsonObject index(JsonObject arguments) {
        // 首次选取采集、移动或施工能力时一次给全名称；比较用途时展开全部概要，两层都不分页。
        boolean summary = arguments.has("detail") && !arguments.get("detail").isJsonNull()
                && "summary".equals(arguments.get("detail").getAsString());
        var ids = IntentRuntime.KNOWN_ABILITIES.stream().filter(id -> !SemanticAbilityCatalog.compatibilityAlias(id)).sorted().toList();
        JsonArray rows = new JsonArray();
        for (String id : ids) {
            if (summary) {
                // 名称还不足以选定动作时才加载用途；只读名称不会构造整份参数契约或查询玩家现场。
                JsonObject row = new JsonObject(); row.addProperty("ability", id);
                row.add("summary", SemanticAbilityCatalog.describe(id).get("summary")); rows.add(row);
            } else rows.add(id);
        }
        JsonObject result = new JsonObject(); result.add("semantic_abilities", rows);
        if (summary) { result.addProperty("total", ids.size()); result.addProperty("contract_loaded", false); }
        return result;
    }

    static JsonObject search(String query) {
        var matcher = new MetadataSearch.Query(query);
        var matches = new ArrayList<JsonObject>();
        for (String ability : IntentRuntime.KNOWN_ABILITIES) {
            if (SemanticAbilityCatalog.compatibilityAlias(ability)) continue;
            // 说明仍来自唯一的能力契约，不另维护一份会漂移的操作清单；完整参数不会进入搜索响应。
            var contract = SemanticAbilityCatalog.describe(ability);
            String summary = contract.get("summary").getAsString();
            String title = ability.substring(ability.indexOf(':') + 1).replace('_', ' ');
            // 接线等子操作常藏在参数说明中；检索这些元数据无需把完整目录发给模型。
            var match = matcher.match(ability, title, ability + " " + contract);
            if (match == null) continue;
            var row = new JsonObject(); row.addProperty("ability", ability);
            row.addProperty("title", title); row.addProperty("summary", summary); row.add("match", match.toJson());
            var read = new JsonObject(); read.addProperty("view", "abilities"); read.addProperty("focus", ability);
            row.add("read_arguments", read); matches.add(row);
        }
        // 搜索仍按相关性排序，但一次交付全部命中，避免模型因数量上限漏掉可选的原生动作。
        var results = new JsonArray(); MetadataSearch.ranked(matches, "ability").forEach(results::add);
        var out = new JsonObject(); out.add("semantic_abilities", results); out.addProperty("query", query);
        out.addProperty("total_matches", matches.size());
        out.addProperty("contract_loaded", false); out.addProperty("runtime_availability", "not_queried");
        matcher.describe(out, matches.isEmpty());
        out.addProperty("next_step", "Use the selected read_arguments to inspect its full contract and current availability. Product names belong to view=knowledge with query. Search does not prove an operation can execute.");
        return out;
    }
}
