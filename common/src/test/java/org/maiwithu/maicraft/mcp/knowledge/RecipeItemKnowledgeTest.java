// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.core.integration.ponder.PonderAccess;
import org.maiwithu.maicraft.core.integration.ponder.PonderTranscript;

/** 思索注册和物品说明随配方发现，知识读取不能编译故事板、猜不存在的教程或触发角色动作。 */
public final class RecipeItemKnowledgeTest {
    public static void main(String[] args) {
        int[] reads = {0};
        String[] status = {"available"};
        PonderAccess access = new PonderAccess() {
            @Override public Snapshot snapshot() {
                reads[0]++;
                return new Snapshot(status[0], "fixture", status[0].equals("available") ? List.of(
                        new Entry("one", "minecraft:stone", "fixture:stone", List.of(), null),
                        new Entry("two", "minecraft:stone", "fixture:stone_use", List.of(), null)) : List.of());
            }
            @Override public PonderTranscript compile(Entry entry) { throw new AssertionError("Discovery must not compile a scene"); }
        };
        var knowledge = new ItemKnowledge(access);
        JsonArray resources = JsonParser.parseString("""
                [{"item_id":"minecraft:stone","roles":["inputs","outputs"],"recipe_indices":[3,4]},
                 {"item_id":"minecraft:hopper","roles":["workstations"],"recipe_indices":[3]},
                 {"item_id":"minecraft:stick"}]
                """).getAsJsonArray();
        JsonObject report = new JsonObject(); knowledge.attach(report, resources);
        check(reads[0] == 1, "one registry snapshot per page");
        var pitfalls = report.getAsJsonArray("pitfalls");
        check(pitfalls.size() == 2, "only registered tutorials and actual usage references become hints");
        var stone = pitfalls.get(0).getAsJsonObject();
        check(stone.getAsJsonArray("recipe_indices").size() == 2 && stone.getAsJsonArray("roles").size() == 2,
                "hints identify all affected recipes and roles");
        var ponder = report.getAsJsonArray("pitfall_notes").get(stone.getAsJsonArray("note_indices").get(0).getAsInt()).getAsJsonObject();
        check(ponder.get("scene_count").getAsInt() == 2 && ponder.getAsJsonObject("read_arguments")
                .get("resource_uri").getAsString().equals(PonderKnowledgeSource.componentUri("minecraft:stone")),
                "confirmed Ponder hints have directly usable read arguments");
        var transfer = report.getAsJsonArray("pitfall_notes").get(pitfalls.get(1).getAsJsonObject()
                .getAsJsonArray("note_indices").get(0).getAsInt()).getAsJsonObject();
        check(transfer.getAsJsonObject("contract").has("source_version")
                && !transfer.getAsJsonObject("contract").get("world_transfer_verified").getAsBoolean(),
                "native transfer reference retains version and unverified world status");
        check(resources.get(2).getAsJsonObject().get("ponder_status").getAsString().equals("no_registered_scenes"),
                "no unregistered tutorial is advertised");
        // 换环境或目录读取失败后，不能沿用上一轮教程，也不能把未知状态说成没有教程。
        for (String unavailable : List.of("not_installed", "api_unavailable", "partial")) {
            status[0] = unavailable; JsonObject unknown = new JsonObject();
            JsonArray item = JsonParser.parseString("[{\"item_id\":\"minecraft:stone\"}]").getAsJsonArray();
            knowledge.attach(unknown, item);
            check(unknown.getAsJsonArray("pitfalls").isEmpty()
                    && item.get(0).getAsJsonObject().get("ponder_status").getAsString().equals("unknown")
                    && unknown.getAsJsonObject("pitfalls_sources").get("ponder_status").getAsString().equals(unavailable),
                    "unavailable Ponder status is preserved without stale hints");
        }
        System.out.println("RecipeItemKnowledgeTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
