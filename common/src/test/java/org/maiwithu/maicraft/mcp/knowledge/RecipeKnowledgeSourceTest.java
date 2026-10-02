// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.core.integration.ponder.PonderAccess;
import org.maiwithu.maicraft.core.integration.ponder.PonderTranscript;

/** 验证材料查询只展开所点的一页，保留未知机制与无匹配事实，不因知识查询触发施工。 */
public final class RecipeKnowledgeSourceTest {
    public static void main(String[] args) {
        int[] reads = {0};
        RecipeKnowledgeSource source = new RecipeKnowledgeSource(query -> {
            reads[0]++;
            check(query.item().toString().equals("minecraft:stone") && query.uses()
                    && query.offset() == 8 && query.limit() == 4, "URI retains exact direction and page");
            return JsonParser.parseString("""
                    {"status":"partial","next_offset":12,"display_recipes":[{
                      "workstations":[{"alternatives":[{"medium":"items","id":"minecraft:furnace"}]}],
                      "inputs":[{"alternatives":[{"medium":"unknown","id":"minecraft:water"},
                        {"medium":"items","id":"minecraft:cobblestone"}]}],
                      "outputs":[{"medium":"items","id":"minecraft:stone"}]
                    }]}
                    """).getAsJsonObject();
        });
        // 搜索发现只读模板；设备和原料的下一层资料不会在这一阶段加载。
        check(source.entries().isEmpty() && source.templates().size() == 1 && reads[0] == 0, "metadata stays lazy");
        check(source.read("file:///private") == null && reads[0] == 0, "foreign resources rejected before read");
        String base = RecipeKnowledgeSource.PREFIX + "minecraft/stone";
        var document = source.read(base + "?limit=4&direction=input&offset=8");
        JsonObject report = JsonParser.parseString(document.text()).getAsJsonObject();
        check(reads[0] == 1 && document.mimeType().equals("application/json"), "one requested page read");
        check(report.get("next_uri").getAsString().equals(base + "?direction=input&offset=12&limit=4"), "next page preserves query");
        check(report.getAsJsonArray("related_resources").size() == 3, "unknown media do not become item dependencies");
        JsonObject workstation = report.getAsJsonArray("related_resources").get(0).getAsJsonObject();
        check(workstation.get("block_uri").getAsString().endsWith("minecraft/furnace")
                && workstation.has("ponder_status") && !workstation.has("ponder_component_candidate_uri"),
                "station links use registry identity and actual tutorial availability");
        check(report.get("status").getAsString().equals("partial"), "partial recipe evidence remains partial");

        // 精确拒绝无效页码、混合方向和重复参数，不能悄悄回到第一页导致规划模型兜圈。
        for (String suffix : List.of("?offset=-1", "?offset=4097", "?offset=999999999999999999",
                "?limit=0", "?limit=17", "?limit=1.0", "?direction=uses", "?direction=input&direction=output",
                "?offset=0&offset=1", "?unknown=1", "?", "?offset=0?limit=1", "#fragment")) {
            try { source.read(base + suffix); throw new AssertionError("invalid query accepted: " + suffix); }
            catch (IllegalArgumentException expected) { /* 错误停在知识请求，不产生实际游戏动作。 */ }
        }
        check(reads[0] == 1, "invalid pages never query provider");
        check(RecipeKnowledgeSource.parse(RecipeKnowledgeSource.PREFIX + "example/nested/material").item().getPath()
                .equals("nested/material"), "nested item IDs retained");
        for (String status : List.of("not_installed", "not_loaded", "no_matches", "api_unavailable")) {
            var unavailable = new RecipeKnowledgeSource(query -> { JsonObject value = new JsonObject(); value.addProperty("status", status); return value; });
            check(JsonParser.parseString(unavailable.read(base).text()).getAsJsonObject().get("status").getAsString().equals(status),
                    "missing EMI and absent matches remain different facts");
        }
        wideAndSharedMaterials();
        System.out.println("RecipeKnowledgeSourceTest: passed");
    }

    private static void wideAndSharedMaterials() {
        // 标签候选多于旧上限，最后一个物品仍有教程；同组在两种角色中复用，不能遗漏或重复读知识。
        JsonObject page = JsonParser.parseString("""
                {"status":"available","display_recipes":[
                  {"index":4,"inputs":[{"alternatives_group":0}],"catalysts":[{"alternatives_group":0}]},
                  {"index":5,"outputs":[{"medium":"items","id":"minecraft:stick"}]}]}
                """).getAsJsonObject();
        JsonArray alternatives = new JsonArray();
        for (int index = 0; index < 35; index++) alternatives.add(stack("example:material_" + index));
        alternatives.add(stack("minecraft:stick"));
        JsonArray groups = new JsonArray(); groups.add(alternatives);
        page.getAsJsonArray("display_recipes").get(0).getAsJsonObject().add("alternative_groups", groups);
        int[] snapshots = {0}, pages = {0};
        var knowledge = new ItemKnowledge(new PonderAccess() {
            @Override public Snapshot snapshot() {
                snapshots[0]++;
                return new Snapshot("available", "fixture", List.of(new Entry("stick", "minecraft:stick", "fixture:stick", List.of(), null)));
            }
            @Override public PonderTranscript compile(Entry entry) { throw new AssertionError("recipe lookup compiled a scene"); }
        });
        var source = new RecipeKnowledgeSource(query -> { pages[0]++; return page; }, knowledge);
        JsonObject result = JsonParser.parseString(source.read(RecipeKnowledgeSource.PREFIX
                + "minecraft/stone?offset=4&limit=2").text()).getAsJsonObject();
        check(pages[0] == 1 && snapshots[0] == 1, "no recursive recipe reads or repeated tutorial discovery");
        check(result.getAsJsonArray("related_resources").size() == 37
                && !result.get("related_resources_truncated").getAsBoolean(), "all visible alternatives plus queried item remain linked");
        var hint = result.getAsJsonArray("pitfalls").get(0).getAsJsonObject();
        check(hint.get("item_id").getAsString().equals("minecraft:stick")
                && hint.getAsJsonArray("recipe_indices").toString().equals("[4,5]")
                && hint.getAsJsonArray("roles").size() == 3, "tail hint retains all shared roles and exact recipe indexes");
        check(!page.has("pitfalls"), "adding hints does not mutate the provider's recipe page");
        // 即使配方提供者没有结果，被查询物品的操作知识仍可用，配方本身仍如实报告无匹配。
        var empty = new RecipeKnowledgeSource(query -> JsonParser.parseString("{\"status\":\"no_matches\"}").getAsJsonObject());
        var hopper = JsonParser.parseString(empty.read(RecipeKnowledgeSource.PREFIX + "minecraft/hopper").text()).getAsJsonObject();
        check(hopper.get("status").getAsString().equals("no_matches") && !hopper.getAsJsonArray("pitfalls").isEmpty(),
                "missing recipes do not hide independent item usage knowledge");
    }

    private static JsonObject stack(String id) {
        JsonObject stack = new JsonObject(); stack.addProperty("medium", "items"); stack.addProperty("id", id);
        // 组件中恰好含有物品 ID 不是配方材料，知识关联不得递归猜测它的含义。
        JsonObject components = new JsonObject(); components.addProperty("unrelated", "minecraft:hopper");
        stack.add("components", components); return stack;
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
