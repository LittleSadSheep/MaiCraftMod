// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import org.maiwithu.maicraft.core.integration.create.CreateTooltipDescription;
import org.maiwithu.maicraft.core.integration.create.CreateTooltipKnowledge;
import org.maiwithu.maicraft.core.integration.ponder.PonderAccess;
import org.maiwithu.maicraft.core.integration.ponder.ReflectivePonderAccess;

/** 查配方时同时交付相关物品的原生用法和已注册思索入口，供模型自行决定下一步如何设计。 */
final class RecipeItemKnowledge {
    private final PonderAccess ponder;
    private final CreateTooltipKnowledge tooltips = new CreateTooltipKnowledge();

    RecipeItemKnowledge() { this(new ReflectivePonderAccess()); }
    RecipeItemKnowledge(PonderAccess ponder) { this.ponder = ponder; }

    void attach(JsonObject report, JsonArray resources) {
        // 每页只读一次注册目录；查材料不会编译所有故事板，更不会启动演示世界或操作玩家。
        PonderAccess.Snapshot snapshot = ponder.snapshot();
        Map<String, Integer> sceneCounts = new LinkedHashMap<>();
        snapshot.entries().forEach(entry -> sceneCounts.merge(entry.component(), 1, Integer::sum));
        JsonObject sources = new JsonObject();
        sources.addProperty("ponder_status", snapshot.status()); sources.addProperty("ponder_detail", snapshot.detail());
        report.add("pitfalls_sources", sources);
        JsonArray pitfalls = new JsonArray();
        for (JsonElement resource : resources) {
            JsonObject link = resource.getAsJsonObject(), item = new JsonObject();
            for (String key : List.of("item_id", "roles", "recipe_indices"))
                if (link.has(key)) item.add(key, link.get(key).deepCopy());
            String id = link.get("item_id").getAsString();
            JsonArray notes = new JsonArray();
            int count = sceneCounts.getOrDefault(id, 0);
            String status = count > 0 ? "available" : List.of("available", "registered_empty").contains(snapshot.status())
                    ? "no_registered_scenes" : "unknown";
            link.addProperty("ponder_status", status);
            if (count > 0) {
                JsonObject note = note("ponder", "installed_ponder_registry");
                note.addProperty("message", "该物品有已注册思索，可按需读取原生操作和结构演示。");
                note.addProperty("scene_count", count);
                reference(note, PonderKnowledgeSource.componentUri(id));
                notes.add(note); link.addProperty("ponder_uri", PonderKnowledgeSource.componentUri(id));
            }
            appendUsage(ResourceLocation.parse(id), link, notes);
            if (!notes.isEmpty()) { item.add("notes", notes); pitfalls.add(item); }
        }
        report.add("pitfalls", pitfalls);
        report.addProperty("pitfalls_scope", "Queried item and item identities visible on this recipe page, including shared alternatives. "
                + "Default-stack descriptions and versioned references are knowledge, not current machine observations or action authorization; scenes are not compiled.");
    }

    private void appendUsage(ResourceLocation id, JsonObject link, JsonArray notes) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            link.addProperty("item_tooltip_status", "item_not_registered"); return;
        }
        var item = BuiltInRegistries.ITEM.get(id);
        var base = CreateTooltipKnowledge.readTooltip(item);
        link.addProperty("item_tooltip_status", base.status());
        if (!base.lines().isEmpty()) {
            JsonObject note = note("item_tooltip", "installed_item_tooltip");
            JsonArray lines = new JsonArray(); base.lines().forEach(lines::add); note.add("lines", lines); notes.add(note);
        }
        var description = tooltips.description(item);
        if (!description.isEmpty()) {
            // 条件、动作与摘要一起返回，避免只读标题后误以为机械手或漏斗会自动供料。
            JsonObject note = note("create_usage", "installed_language_resources");
            note.addProperty("translation_key", description.translationKey());
            note.addProperty("summary", description.summary());
            note.add("behaviours", details(description.behaviours())); note.add("controls", details(description.controls()));
            notes.add(note);
        }
        if (item instanceof BlockItem blockItem) {
            ResourceLocation block = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
            JsonObject transfer = NativeItemTransferContract.reference(block.toString());
            if (transfer != null) {
                // 已审阅的传输边界随相关设备出现；保留适用版本与待现场确认项，不据此替模型决定布局。
                JsonObject note = note("native_item_transfer", "audited_native_source_reference");
                note.add("contract", transfer);
                reference(note, MinecraftKnowledgeSource.BLOCK + block.getNamespace() + "/" + block.getPath());
                notes.add(note);
            }
        }
    }

    private static JsonArray details(List<CreateTooltipDescription.Detail> details) {
        JsonArray result = new JsonArray();
        for (var detail : details) {
            JsonObject row = new JsonObject(); row.addProperty("condition", detail.condition());
            row.addProperty("explanation", detail.explanation()); result.add(row);
        }
        return result;
    }

    private static JsonObject note(String kind, String source) {
        JsonObject result = new JsonObject(); result.addProperty("kind", kind); result.addProperty("source", source); return result;
    }

    private static void reference(JsonObject note, String uri) {
        note.addProperty("resource_uri", uri);
        JsonObject arguments = new JsonObject(); arguments.addProperty("view", "knowledge"); arguments.addProperty("resource_uri", uri);
        note.add("read_arguments", arguments);
    }
}
