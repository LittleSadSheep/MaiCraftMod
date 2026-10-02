package org.maiwithu.maicraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.task.explore.ClientExplorationMemory;
import org.maiwithu.maicraft.core.task.explore.ExplorationFinding;
import org.maiwithu.maicraft.core.task.explore.ExplorationJournal;
import org.maiwithu.maicraft.intent.persistence.ExplorationMemoryStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 默认只给地点摘要与下一页；精确 focus 才展开观察证据，历史记忆不会混进身体状态。 */
public final class ExplorationMemoryView {
    private ExplorationMemoryView() {}

    public static JsonObject read(StateIdentity identity, String focus, String query, int offset, int limit) {
        var store = new ExplorationMemoryStore(identity);
        List<ExplorationJournal> pending = ClientExplorationMemory.pending(identity);
        int pendingAtStart = pending.stream().mapToInt(ExplorationJournal::pendingCount).sum();
        pending.forEach(ExplorationJournal::flush);
        try {
            JsonObject result;
            if (focus != null && focus.startsWith("exploration:")) {
                if (query != null || offset != 0) throw new IllegalArgumentException("exact discovery focus cannot be combined with query/offset");
                String id = focus.substring("exploration:".length());
                var finding = ClientExplorationMemory.pendingFinding(identity, id);
                boolean saved = finding == null;
                if (saved) finding = store.find(id);
                if (finding == null) throw new IllegalArgumentException("discovery is not known in this world");
                result = summary(finding);
                result.addProperty("save_state", saved ? "saved" : "pending_observation_only");
                result.add("observed_position", new Gson().toJsonTree(Map.of("x", finding.x(), "y", finding.y(), "z", finding.z())));
                result.add("evidence", JsonParser.parseString(finding.evidenceJson()));
            } else if ("pending".equals(focus)) {
                Map<String, ExplorationFinding> findings = new LinkedHashMap<>();
                pending.stream().flatMap(journal -> journal.pendingFindings().stream())
                        .forEach(finding -> findings.merge(finding.id(), finding, ExplorationFinding::merge));
                List<JsonObject> rows = findings.values().stream().map(finding -> {
                    JsonObject row = summary(finding); row.add("evidence", JsonParser.parseString(finding.evidenceJson())); return row;
                }).toList();
                result = ExplorationCatalog.page("pending", rows, query, offset, limit);
            } else if (focus == null || "discoveries".equals(focus) || focus.startsWith("run:")) {
                boolean run = focus != null && focus.startsWith("run:");
                if (run && query != null) throw new IllegalArgumentException("run focus already selects discoveries; omit query");
                var page = run ? store.queryRun(focus.substring(4), offset, limit) : store.query(query, offset, limit);
                result = new JsonObject();
                result.addProperty("total", page.total()); result.addProperty("offset", offset);
                JsonArray entries = new JsonArray(); page.entries().forEach(finding -> entries.add(summary(finding)));
                result.add("entries", entries);
                if (page.nextOffset() != null) {
                    result.addProperty("next_offset", page.nextOffset());
                    JsonObject next = new JsonObject(); next.addProperty("view", "exploration"); next.addProperty("focus", run ? focus : "discoveries");
                    if (query != null) next.addProperty("query", query);
                    next.addProperty("offset", page.nextOffset()); next.addProperty("limit", limit); result.add("next_query", next);
                }
            } else throw new IllegalArgumentException("exploration focus: discoveries, pending, biomes, biome_tags, structures, or a returned details_focus");
            result.addProperty("evidence_scope", "historical_observation; recheck_current_world_before_relying_on_it");
            // 查询和后台保存可以同时完成；保留开查时的待写量，避免把尚未进入读取快照的地点误说成没有发现。
            int pendingCount = Math.max(pendingAtStart, pending.stream().mapToInt(ExplorationJournal::pendingCount).sum());
            result.addProperty("pending_places", pendingCount);
            if (pendingCount > 0) result.add("pending_query", new Gson().toJsonTree(Map.of("view", "exploration", "focus", "pending")));
            var failures = pending.stream().map(ExplorationJournal::failure).filter(error -> error != null).distinct().toList();
            if (!failures.isEmpty()) result.add("save_errors", new Gson().toJsonTree(failures));
            result.add("catalogs", new Gson().toJsonTree(List.of("biomes", "biome_tags", "structures")));
            return result;
        } catch (IOException failure) { throw new IllegalStateException("exploration memory query failed", failure); }
    }

    private static JsonObject summary(ExplorationFinding finding) {
        JsonObject row = new JsonObject();
        row.addProperty("id", finding.id()); row.addProperty("kind", finding.kind());
        row.addProperty("target_id", finding.targetId()); row.addProperty("name", finding.name());
        row.addProperty("dimension", finding.dimension()); row.addProperty("visited_region", finding.visited());
        row.addProperty("first_observed", Instant.ofEpochMilli(finding.firstSeen()).toString());
        row.addProperty("last_observed", Instant.ofEpochMilli(finding.lastSeen()).toString());
        row.addProperty("details_focus", "exploration:" + finding.id());
        JsonObject target = new JsonObject(); target.addProperty("kind", "landmark"); target.addProperty("label", "exploration:" + finding.id());
        row.add("travel_target", target);
        return row;
    }
}
