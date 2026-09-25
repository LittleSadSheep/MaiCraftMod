package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 大块游戏证据按 JSON Pointer 找回；摘要明确标记省略，分页不把截断当成完整事实。 */
final class JsonReadback {
    private static final int TEXT_PAGE = 4000;
    private JsonReadback() {}

    static JsonObject page(JsonElement root, String path, int offset, int limit) {
        return page(root, path, offset, limit, null);
    }

    static JsonObject page(JsonElement root, String path, int offset, int limit, Function<String, String> uri) {
        if (offset < 0 || limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid detail page");
        JsonElement value = resolve(root, path);
        JsonObject page = reference(value, path);
        page.remove("detail_path"); page.addProperty("path", path);
        page.addProperty("offset", offset);
        // 大文本按字符连续读取，边界避开代理对；调用者复制 next_offset 就不会漏掉中文或表情。
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (offset > text.length() || offset > 0 && offset < text.length()
                    && Character.isLowSurrogate(text.charAt(offset)) && Character.isHighSurrogate(text.charAt(offset - 1)))
                throw new IllegalArgumentException("Invalid text offset");
            int end = Math.min(text.length(), offset + TEXT_PAGE);
            // 引号、反斜杠和控制字符序列化后会变长；按 JSON 大小缩短当前页，避免一页正文又被外层二次归档。
            while (end > offset && !fits(new JsonPrimitive(text.substring(offset, end)), 5000))
                end = offset + (end - offset) / 2;
            if (end < text.length() && end > offset && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            page.addProperty("value", text.substring(offset, end));
            if (end < text.length()) page.addProperty("next_offset", end);
            return page;
        }
        if (!value.isJsonArray() && !value.isJsonObject()) {
            if (offset != 0) throw new IllegalArgumentException("Scalar detail has no offset");
            page.add("value", value.deepCopy()); return page;
        }
        int total = value.isJsonArray() ? value.getAsJsonArray().size() : value.getAsJsonObject().size();
        if (offset > total) throw new IllegalArgumentException("Detail offset exceeds collection size");
        List<String> keys = value.isJsonObject() ? new ArrayList<>(value.getAsJsonObject().keySet()) : List.of();
        JsonArray rows = new JsonArray();
        int end = offset, wanted = Math.min(total, offset + limit), budget = Math.max(180, 5000 / limit);
        for (int i = offset; i < wanted; i++) {
            String key = value.isJsonArray() ? Integer.toString(i) : keys.get(i);
            JsonObject row = new JsonObject();
            if (value.isJsonArray()) row.addProperty("index", i); else row.addProperty("key", key);
            JsonElement child = value.isJsonArray() ? value.getAsJsonArray().get(i) : value.getAsJsonObject().get(key);
            row.add("value", preview(child, childPath(path, key), budget, uri)); rows.add(row);
            // 长路径和 URI 也占上下文；达到本页预算就沿 next_offset 续读，不跳过尚未交付的一项。
            if (rows.size() > 1 && !fits(rows, 5500)) { rows.remove(rows.size() - 1); break; }
            end = i + 1;
        }
        page.add("items", rows);
        if (end < total) page.addProperty("next_offset", end);
        return page;
    }

    static JsonElement preview(JsonElement value, String path, int budget) {
        return preview(value, path, budget, null);
    }

    static boolean fits(JsonElement value, int budget) { return size(value, budget) <= budget; }

    static JsonElement preview(JsonElement value, String path, int budget, Function<String, String> uri) {
        if (size(value, budget) <= budget) return value.deepCopy();
        JsonObject reference = reference(value, path);
        reference.addProperty("omitted", true);
        if (uri != null) reference.addProperty("resource_uri", uri.apply(path));
        // 证据过大时只带直接状态事实，完整数据仍由原任务或资源提供；不抽取任意前几行冒充代表性样本。
        if (value.isJsonObject()) {
            JsonObject summary = new JsonObject();
            var entries = new ArrayList<>(value.getAsJsonObject().entrySet());
            entries.sort(Comparator.comparingInt(entry -> priority(entry.getKey(), entry.getValue())));
            for (var entry : entries) {
                if (!entry.getValue().isJsonPrimitive() || size(entry.getValue(), 240) > 240) continue;
                summary.add(entry.getKey(), entry.getValue().deepCopy());
                if (size(summary, Math.max(0, budget - 180)) > Math.max(0, budget - 180)) {
                    summary.remove(entry.getKey()); break;
                }
            }
            if (!summary.isEmpty()) reference.add("summary", summary);
        }
        return reference;
    }

    static JsonElement resolve(JsonElement root, String path) {
        if (path == null || !path.isEmpty() && !path.startsWith("/"))
            throw new IllegalArgumentException("path must be a JSON Pointer; use empty string for the root");
        JsonElement current = root;
        if (path.isEmpty()) return current;
        for (String raw : path.substring(1).split("/", -1)) {
            for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) == '~'
                    && (i + 1 == raw.length() || raw.charAt(i + 1) != '0' && raw.charAt(i + 1) != '1'))
                throw new IllegalArgumentException("Invalid JSON Pointer escape");
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current.isJsonObject() && current.getAsJsonObject().has(key)) current = current.getAsJsonObject().get(key);
            else if (current.isJsonArray() && key.matches("0|[1-9][0-9]*")) {
                try { current = current.getAsJsonArray().get(Integer.parseInt(key)); }
                catch (IndexOutOfBoundsException | NumberFormatException invalid) { throw missing(path); }
            } else throw missing(path);
        }
        return current;
    }

    static String childPath(String parent, String key) {
        return parent + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static JsonObject reference(JsonElement value, String path) {
        JsonObject result = new JsonObject(); result.addProperty("detail_path", path);
        String kind = value.isJsonArray() ? "array" : value.isJsonObject() ? "object"
                : value.isJsonNull() ? "null" : value.getAsJsonPrimitive().isString() ? "string" : "scalar";
        result.addProperty("type", kind);
        if (value.isJsonArray()) result.addProperty("total", value.getAsJsonArray().size());
        if (value.isJsonObject()) result.addProperty("total", value.getAsJsonObject().size());
        if (kind.equals("string")) result.addProperty("total", value.getAsString().length());
        return result;
    }

    private static int priority(String key, JsonElement value) {
        // 不确定消费、禁止重试和未结产物优先于普通计数，摘要不能先花完篇幅再漏掉停止理由。
        if (List.of("outcome_uncertain", "mechanical_retry_allowed", "effects_started", "pending_output",
                "success", "code", "state", "reason", "message").contains(key)) return 0;
        return value.isJsonPrimitive() && !value.getAsJsonPrimitive().isString() ? 1 : 2;
    }

    private static IllegalArgumentException missing(String path) {
        return new IllegalArgumentException("Detail path is absent: " + path);
    }

    private static int size(JsonElement value, int cap) {
        // 只数到本次内联预算；几万格蓝图不先整体转成字符串，避免一次摘要又生成大份临时文本。
        if (cap < 0) return 1;
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isString() && value.getAsString().length() > cap) return cap + 1;
            return Math.min(cap + 1, value.toString().length());
        }
        int count = 2;
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) {
            count += size(new JsonPrimitive(entry.getKey()), cap - count) + 2;
            count += size(entry.getValue(), cap - count);
            if (count > cap) return cap + 1;
        }
        else if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) {
            count += size(child, cap - count) + 1;
            if (count > cap) return cap + 1;
        }
        else count = 4;
        return Math.min(cap + 1, count);
    }
}
