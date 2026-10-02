// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 宽标签下的同源操作说明可共享，角色关系、不同版本和末尾物品仍须在当前回执完整还原。 */
public final class KnowledgePitfallsTest {
    public static void main(String[] args) {
        JsonArray items = new JsonArray();
        for (int index = 0; index < 80; index++) {
            var item = JsonParser.parseString("""
                    {"roles":["inputs","outputs"],"recipe_indices":[4,5],
                     "recipe_roles":{"4":["inputs"],"5":["outputs"]},
                     "notes":[{"kind":"create_usage","source":"installed_language_resources","version":"one"}]}
                    """).getAsJsonObject();
            item.addProperty("item_id", "example:material_" + index);
            var note = item.getAsJsonArray("notes").get(0).getAsJsonObject();
            note.addProperty("condition", "操作顺序与前提".repeat(200));
            if (index == 79) note.addProperty("version", "two");
            items.add(item);
        }
        var original = items.deepCopy(); JsonObject report = new JsonObject(); KnowledgePitfalls.present(report, items);
        check(items.equals(original), "grouping leaves captured native knowledge unchanged");
        check(report.getAsJsonArray("pitfall_notes").size() == 2, "only identical source and content share a definition");
        var pitfalls = report.getAsJsonArray("pitfalls");
        check(pitfalls.size() == 80, "every relevant item remains visible");
        for (int index = 0; index < 80; index++) {
            var item = pitfalls.get(index).getAsJsonObject(); JsonArray restored = new JsonArray();
            for (var noteIndex : item.getAsJsonArray("note_indices")) restored.add(report.getAsJsonArray("pitfall_notes").get(noteIndex.getAsInt()));
            check(restored.equals(original.get(index).getAsJsonObject().get("notes")), "all conditions restore from this response alone");
        }
        var recipes = report.getAsJsonObject("pitfall_index").getAsJsonArray("recipes");
        var first = recipes.get(0).getAsJsonObject().getAsJsonObject("roles");
        var second = recipes.get(1).getAsJsonObject().getAsJsonObject("roles");
        check(first.getAsJsonArray("inputs").size() == 80 && !first.has("outputs")
                && second.getAsJsonArray("outputs").size() == 80 && !second.has("inputs"), "roles stay bound to the actual recipe");
        JsonObject later = new JsonObject(); KnowledgePitfalls.present(later, items);
        check(later.equals(report), "a later request carries full definitions even after earlier context is forgotten");
        check(report.toString().length() < original.toString().length() / 2, "shared long explanations reduce repetition without losing facts");
        System.out.println("KnowledgePitfallsTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
