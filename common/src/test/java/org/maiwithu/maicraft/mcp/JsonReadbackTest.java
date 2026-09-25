package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** 上下文压缩后按路径找回原证据，覆盖大数组、特殊字段名和跨页表情。 */
public final class JsonReadbackTest {
    public static void main(String[] args) {
        JsonObject root = new JsonObject(); JsonArray rows = new JsonArray();
        for (int i = 0; i < 151; i++) rows.add("confirmed:" + i);
        root.add("a/b~c", rows);
        String path = JsonReadback.childPath("", "a/b~c");
        check(path.equals("/a~1b~0c") && JsonReadback.resolve(root, path).equals(rows), "escaped pointer");
        JsonObject compact = JsonReadback.preview(rows, path, 200).getAsJsonObject();
        check(compact.get("total").getAsInt() == 151 && compact.get("detail_path").getAsString().equals(path), "omission is recoverable");
        JsonArray recovered = new JsonArray(); int offset = 0;
        while (true) {
            var page = JsonReadback.page(root, path, offset, 7);
            page.getAsJsonArray("items").forEach(row -> recovered.add(row.getAsJsonObject().get("value")));
            if (!page.has("next_offset")) break;
            offset = page.get("next_offset").getAsInt();
        }
        check(recovered.equals(rows), "no gap or duplicate across pages");
        String text = "中".repeat(3999) + "🌲" + "文".repeat(4500);
        root.addProperty("text", text); StringBuilder restored = new StringBuilder(); offset = 0;
        while (true) {
            var page = JsonReadback.page(root, "/text", offset, 1);
            restored.append(page.get("value").getAsString());
            if (!page.has("next_offset")) break;
            offset = page.get("next_offset").getAsInt();
        }
        check(restored.toString().equals(text), "text and surrogate pairs survive paging");
        root.add("", new JsonPrimitive(false));
        check(!JsonReadback.resolve(root, "/").getAsBoolean(), "empty object key is distinct from root");
        root.addProperty("line\n~bad", true);
        for (String invalid : new String[]{"a", "/missing", "/a~2b", "/line\n~bad", path + "/01", path + "/99999999999999999"}) {
            try { JsonReadback.resolve(root, invalid); throw new AssertionError("accepted " + invalid); }
            catch (IllegalArgumentException expected) { /* 找不到的证据明确失败，不回空值让调用者误判。 */ }
        }
        root.addProperty("outcome_uncertain", true); root.addProperty("mechanical_retry_allowed", false);
        var preview = JsonReadback.preview(root, "", 800).getAsJsonObject();
        check(preview.getAsJsonObject("summary").get("outcome_uncertain").getAsBoolean()
                && !preview.getAsJsonObject("summary").get("mechanical_retry_allowed").getAsBoolean(), "uncertainty survives preview");
        check(JsonReadback.page(root, "", 0, 10).toString().length() < 7000 && rows.size() == 151, "bounded page leaves source intact");
        System.out.println("JsonReadbackTest: passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
