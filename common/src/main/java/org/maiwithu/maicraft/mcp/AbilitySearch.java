// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;

/** 能力发现只返回标识和用途；选定能力后才读取完整契约与现场可用性。 */
final class AbilitySearch {
    private AbilitySearch() {}

    static JsonObject search(String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Ability search limit must be 1..20");
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
        var results = new JsonArray(); MetadataSearch.ranked(matches, "ability").stream().limit(limit).forEach(results::add);
        var out = new JsonObject(); out.add("semantic_abilities", results); out.addProperty("query", query);
        out.addProperty("total_matches", matches.size()); out.addProperty("truncated", matches.size() > limit);
        out.addProperty("contract_loaded", false); out.addProperty("runtime_availability", "not_queried");
        matcher.describe(out, matches.isEmpty());
        out.addProperty("next_step", "Use the selected read_arguments to inspect its full contract and current availability. Product names belong to view=knowledge with query. Search does not prove an operation can execute.");
        return out;
    }
}
