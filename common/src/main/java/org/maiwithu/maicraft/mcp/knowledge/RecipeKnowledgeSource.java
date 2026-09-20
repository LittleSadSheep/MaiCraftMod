// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import org.maiwithu.maicraft.core.integration.emi.EmiRecipeKnowledge;
import org.maiwithu.maicraft.core.integration.machine.MachineRecipeEvidence;

/** 材料目标只展开一页来源或用途；下一层原料、工作站与教程由调用者按需要继续读取。 */
public final class RecipeKnowledgeSource implements KnowledgeLibrary.Source {
    public static final String PREFIX = "maicraft://knowledge/recipes/";
    public static final int DEFAULT_LIMIT = 8;
    @FunctionalInterface interface Reader { JsonObject read(Query query); }
    record Query(ResourceLocation item, boolean uses, int offset, int limit) {}
    private final Reader reader;

    public RecipeKnowledgeSource() { this(RecipeKnowledgeSource::inspect); }
    RecipeKnowledgeSource(Reader reader) { this.reader = reader; }

    // 目录与搜索不加载配方正文，避免一个材料目标把整张合成树塞进外部规划模型的上下文。
    @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
    @Override public JsonArray templates() {
        JsonArray result = new JsonArray();
        JsonObject template = PonderKnowledgeSource.template(
                PREFIX + "{namespace}/{+path}{?direction,offset,limit}", "recipes.item",
                "物品的 EMI 来源或用途；direction=output|input，offset=0..4096，limit=1..16，默认8条");
        template.addProperty("mimeType", "application/json"); result.add(template); return result;
    }

    public static String uri(ResourceLocation id) { return PREFIX + id.getNamespace() + "/" + id.getPath(); }
    static KnowledgeDocument.Entry entry(ResourceLocation id, String name) {
        return new KnowledgeDocument.Entry(uri(id), "recipes." + id, name + " · 材料与工艺",
                "EMI 原料、产物、催化剂与工作站；按需读取，未验证机器执行能力。", id.toString(), "application/json");
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!uri.startsWith(PREFIX)) return null;
        Query query = parse(uri);
        JsonObject report = reader.read(query).deepCopy();
        report.addProperty("resource_uri", uri);
        report.addProperty("planning_guide", KnowledgeLibrary.RECIPES);
        report.addProperty("process_contracts", KnowledgeLibrary.PROCESSES);
        linkMaterials(report);
        // 页码只是当前索引位置；重载后应重新读第一页，不能把旧配方序号当成稳定配方身份。
        if (report.has("next_offset")) report.addProperty("next_uri", uri(query.item())
                + "?direction=" + (query.uses() ? "input" : "output")
                + "&offset=" + report.get("next_offset").getAsInt() + "&limit=" + query.limit());
        return new KnowledgeDocument(uri, "recipes." + query.item(), query.item() + " · 材料与工艺",
                "当前游戏中的只读配方知识；不执行填充、建造或生产。", report.toString(), "application/json");
    }

    static Query parse(String uri) {
        String[] parts = uri.substring(PREFIX.length()).split("\\?", -1);
        if (parts.length > 2 || parts[0].contains("#")) throw new IllegalArgumentException("Invalid recipe resource URI");
        int slash = parts[0].indexOf('/');
        ResourceLocation id = slash <= 0 ? null : ResourceLocation.tryParse(
                parts[0].substring(0, slash) + ":" + parts[0].substring(slash + 1));
        if (id == null || id.getPath().isEmpty()) throw new IllegalArgumentException("Recipe resource needs namespace/item_path");
        boolean uses = false; int offset = 0, limit = DEFAULT_LIMIT; Set<String> seen = new HashSet<>();
        if (parts.length == 2) for (String parameter : parts[1].split("&", -1)) {
            String[] pair = parameter.split("=", -1);
            if (pair.length != 2 || !seen.add(pair[0])) throw new IllegalArgumentException("Invalid or duplicate recipe parameter");
            switch (pair[0]) {
                case "direction" -> {
                    if (!pair[1].equals("input") && !pair[1].equals("output"))
                        throw new IllegalArgumentException("Recipe direction must be input or output");
                    uses = pair[1].equals("input");
                }
                case "offset" -> offset = integer(pair[1], 0, EmiRecipeKnowledge.MAX_OFFSET);
                case "limit" -> limit = integer(pair[1], 1, EmiRecipeKnowledge.MAX_LIMIT);
                default -> throw new IllegalArgumentException("Unknown recipe parameter: " + pair[0]);
            }
        }
        return new Query(id, uses, offset, limit);
    }

    private static int integer(String value, int minimum, int maximum) {
        if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Recipe pagination requires exact integers");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) { /* 超大页码也按无效参数返回，不退回第一页。 */ }
        throw new IllegalArgumentException("Recipe pagination is out of range");
    }

    private static JsonObject inspect(Query query) {
        if (!BuiltInRegistries.ITEM.containsKey(query.item())) throw KnowledgeException.missing(uri(query.item()));
        var player = Minecraft.getInstance().player;
        JsonObject report = EmiRecipeKnowledge.inspect(player, query.item(), query.uses(), query.offset(), query.limit());
        String status = report.get("status").getAsString();
        // EMI缺失或未覆盖时仍保留原生配方表的有限展示证据；用途页和后续页不重复扫描整份同步表。
        if (!query.uses() && query.offset() == 0 && !Set.of("available", "partial").contains(status))
            report.add("native_fallback", MachineRecipeEvidence.inspect(player, query.item().toString()));
        return report;
    }

    private static void linkMaterials(JsonObject report) {
        JsonArray links = new JsonArray(); Set<String> seen = new LinkedHashSet<>();
        JsonArray rows = report.has("display_recipes") ? report.getAsJsonArray("display_recipes") : new JsonArray();
        // 先给设备候选提供教程入口，再给原料提供下一层配方入口；不访问组件内部的任意ID，也不展开后继配方。
        for (String role : List.of("workstations", "catalysts", "inputs", "outputs")) for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            if (!row.has(role)) continue;
            for (JsonElement ingredient : row.getAsJsonArray(role)) {
                JsonObject value = ingredient.getAsJsonObject();
                if (value.has("alternatives")) for (JsonElement stack : value.getAsJsonArray("alternatives"))
                    linkStack(stack.getAsJsonObject(), links, seen);
                else linkStack(value, links, seen);
            }
        }
        report.add("related_resources", links);
        report.addProperty("related_resources_truncated", seen.size() > links.size());
    }

    private static void linkStack(JsonObject stack, JsonArray links, Set<String> seen) {
        if (!stack.has("medium") || !"items".equals(stack.get("medium").getAsString())
                || !stack.has("id") || stack.get("id").isJsonNull()) return;
        String value = stack.get("id").getAsString();
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !seen.add(value) || links.size() >= 32) return;
        JsonObject link = new JsonObject(); link.addProperty("item_id", value); link.addProperty("recipe_uri", uri(id));
        // Ponder可关联工具或方块；链接只指向候选目录，不为了补链接而提前编译教程，也不保证存在场景。
        link.addProperty("ponder_component_candidate_uri", PonderKnowledgeSource.componentUri(value));
        if (BuiltInRegistries.ITEM.get(id) instanceof BlockItem blockItem) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
            link.addProperty("block_uri", MinecraftKnowledgeSource.BLOCK + blockId.getNamespace() + "/" + blockId.getPath());
        }
        links.add(link);
    }
}
