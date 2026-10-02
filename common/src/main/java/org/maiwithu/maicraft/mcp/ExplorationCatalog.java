package org.maiwithu.maicraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.core.task.structure.StructureEvidenceProfiles;
import org.maiwithu.maicraft.core.task.structure.StructureProfileResources;

/** 只在请求时列出当前模组环境的探索种类；登记过的群系不代表角色已经去过那里。 */
public final class ExplorationCatalog {
    private ExplorationCatalog() {}

    public static JsonObject read(HolderLookup.Provider registries, String category, String query, int offset, int limit) {
        List<JsonObject> rows = new ArrayList<>();
        var biomes = registries.lookupOrThrow(Registries.BIOME);
        switch (category) {
            case "biomes" -> biomes.listElements().forEach(holder -> {
                String id = holder.key().location().toString();
                JsonObject item = named(id, "biome");
                item.addProperty("temperature", holder.value().getBaseTemperature());
                item.addProperty("precipitation", holder.value().hasPrecipitation());
                JsonArray tags = new JsonArray();
                holder.tags().map(tag -> tag.location().toString()).sorted().forEach(tags::add);
                item.add("tags", tags);
                rows.add(item);
            });
            case "biome_tags" -> biomes.listTags().forEach(tag -> {
                JsonObject item = new JsonObject();
                item.addProperty("id", "#" + tag.key().location());
                item.addProperty("biome_count", tag.size());
                // 模组可把大量群系放进同一标签；成员继续用群系目录分页，不能一次展开整包名单。
                JsonObject members = new JsonObject(); members.addProperty("view", "exploration");
                members.addProperty("focus", "biomes"); members.addProperty("query", tag.key().location().toString());
                item.add("members_query", members);
                rows.add(item);
            });
            case "structures" -> {
                StructureProfileResources.refresh();
                Set<String> ids = new LinkedHashSet<>(StructureEvidenceProfiles.registeredIds());
                registries.lookup(Registries.STRUCTURE).ifPresent(registry -> registry.listElements()
                        .forEach(holder -> ids.add(holder.key().location().toString())));
                for (String id : ids) {
                    JsonObject item = named(id, "structure");
                    var profile = StructureEvidenceProfiles.resolve(id);
                    item.addProperty("searchable", profile != null);
                    if (profile != null) {
                        item.addProperty("recognition", "visible_block_pattern; natural_generation_not_proven");
                        item.addProperty("evidence", profile.profile().evidenceDescription());
                        item.add("dimensions", new Gson().toJsonTree(profile.profile().dimensions().stream().sorted().toList()));
                        item.addProperty("canonical_profile", profile.profile().canonicalId());
                    } else item.addProperty("reason", "No usable visible-evidence profile in this client; registry presence gives no location.");
                    rows.add(item);
                }
            }
            default -> throw new IllegalArgumentException("exploration catalog focus must be biomes, biome_tags or structures");
        }
        JsonObject result = page(category, rows, query, offset, limit);
        if ("structures".equals(category)) {
            // 多人客户端可能没有结构生成注册表，说明目录来源，不能把证据别名冒充服务器完整名单。
            result.addProperty("native_structure_registry_available", registries.lookup(Registries.STRUCTURE).isPresent());
            result.addProperty("catalog_sources", "available_native_registry_and_visible_evidence_profiles");
            if (!StructureProfileResources.problems().isEmpty())
                result.add("profile_errors", new Gson().toJsonTree(StructureProfileResources.problems()));
        }
        return result;
    }

    /** 查询按名称、模组 ID 和标签匹配；固定排序和显式 next_offset 保留全部结果，不静默截断。 */
    static JsonObject page(String category, List<JsonObject> rows, String query, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 20) throw new IllegalArgumentException("invalid exploration page");
        String[] words = query == null ? new String[0] : query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        var matches = rows.stream().filter(row -> {
            String searchable = row.toString().toLowerCase(Locale.ROOT);
            for (String word : words) if (!searchable.contains(word)) return false;
            return true;
        }).sorted(Comparator.comparing(row -> row.get("id").getAsString())).toList();
        int start = Math.min(offset, matches.size());
        int end = (int) Math.min((long) start + limit, matches.size());
        JsonObject result = new JsonObject();
        result.addProperty("category", category);
        result.addProperty("evidence_scope", "registered_metadata_not_discovered_places");
        result.addProperty("total", matches.size());
        result.addProperty("offset", offset);
        result.add("entries", new Gson().toJsonTree(matches.subList(start, end)));
        if (end < matches.size()) {
            result.addProperty("next_offset", end);
            JsonObject next = new JsonObject();
            next.addProperty("view", "exploration"); next.addProperty("focus", category);
            if (query != null) next.addProperty("query", query);
            next.addProperty("offset", end); next.addProperty("limit", limit);
            result.add("next_query", next);
        }
        return result;
    }

    private static JsonObject named(String id, String type) {
        JsonObject item = new JsonObject(); item.addProperty("id", id);
        item.addProperty("name", Component.translatable(type + "." + id.replace(':', '.').replace('/', '.')).getString());
        return item;
    }
}
