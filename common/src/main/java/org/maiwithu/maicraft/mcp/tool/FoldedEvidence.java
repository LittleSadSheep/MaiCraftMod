// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 任务结果里折起来、按路径读的大块证据。目标的默认样子（goal 查到的、events 带 goal_id 附上的）只留
 * status、summary、problem 和小块的事实；试过的办法、细节里超过约 4KB 的一项、超过约 4KB 的列表
 * 从结果里拿掉，在 {@code folded} 里写明路径、形状、几项、多少字。{@code goal(get, goal_id, path)} 读回那一段，
 * 列表与对象再带 offset / limit 翻页。
 *
 * <p>不是删：拿掉的每一块都在 folded 里留了路径与大小，按路径读得回原样，读到的那一段里再大的子块照同样的规矩折起来。
 * 结果留在目标运行表里，按路径读时现算，不另存一份。
 */
final class FoldedEvidence {

    /** 一块证据超过多少字就折起来（按 JSON 的字数算）。 */
    static final int FOLD_CHARS = 4096;
    /** 细节折起大的几项后，剩下的小项合起来超过这么多字（几百个小项），整块折起来按路径翻页读。 */
    static final int DETAILS_KEPT_CHARS = 2 * FOLD_CHARS;
    /** 按路径读时不给 limit，一页按这么多字装，至少装一项。 */
    static final int PAGE_CHARS = 16_384;
    /** 按路径读时一页最多几项。 */
    static final int MAX_LIMIT = 200;
    /** 默认样子里始终留着的：达成情况、一句话结论、卡在哪。 */
    private static final Set<String> ALWAYS_SHOWN = Set.of("status", "summary", "problem");
    /** 试过的办法：做下一步决定时先看结论与问题，办法的明细按路径读。 */
    private static final String ATTEMPTS = "attempts";
    /** 能力特有的细节：整体不大就整块留着，大了只折起大的那几项。 */
    private static final String DETAILS = "details";

    private FoldedEvidence() {}

    /** 结果的默认样子：大块证据折起来，路径与大小列进 folded；没有要折的就和原样一样。 */
    static JsonObject fold(JsonObject result) {
        JsonObject shown = new JsonObject();
        JsonArray folded = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : result.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            String path = childPath("", key);
            if (ALWAYS_SHOWN.contains(key) || isEmpty(value)) {
                shown.add(key, value);
            } else if (key.equals(ATTEMPTS)) {
                folded.add(reference(path, value));
            } else if (chars(value) <= FOLD_CHARS) {
                shown.add(key, value);
            } else if (key.equals(DETAILS) && value.isJsonObject()) {
                foldDetails(value.getAsJsonObject(), path, shown, folded);
            } else {
                folded.add(reference(path, value));
            }
        }
        if (!folded.isEmpty()) shown.add("folded", folded);
        return shown;
    }

    // 细节：只折起大的那几项，其余留着；折完还是太大（几百个小项），整块折起来按路径翻页读。
    private static void foldDetails(JsonObject details, String path, JsonObject shown, JsonArray folded) {
        JsonArray inner = new JsonArray();
        JsonObject kept = foldChildren(details, path, inner);
        if (chars(kept) <= DETAILS_KEPT_CHARS) {
            shown.add(DETAILS, kept);
            folded.addAll(inner);
        } else {
            folded.add(reference(path, details));
        }
    }

    // 对象里超过一折大小的子项换成引用，记进 folded；小的照原样留着。
    private static JsonObject foldChildren(JsonObject object, String path, JsonArray folded) {
        JsonObject kept = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (chars(entry.getValue()) > FOLD_CHARS) {
                folded.add(reference(childPath(path, entry.getKey()), entry.getValue()));
            } else {
                kept.add(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    /**
     * 按路径读结果里的一段。列表与对象按 offset 起翻页：给了 limit 就按项数，不给就按一页的字数装（至少一项）；
     * 读到的子项里再大的照样折起来，路径列进 folded。一句文字、一个值整段给，不分页。
     *
     * @param path   JSON Pointer，照抄 folded 里的 path，例如 /attempts、/details/cells
     * @param offset 从第几项读起；不给为 0
     * @param limit  这一页最多几项；不给按字数装
     * @throws IllegalArgumentException 路径写法不对、结果里没有这一段，或给一句文字带了翻页
     */
    static JsonObject read(JsonObject result, String path, Integer offset, Integer limit) {
        JsonElement value = resolve(result, path);
        JsonObject page = new JsonObject();
        page.addProperty("path", path);
        page.addProperty("type", type(value));
        if (!value.isJsonArray() && !value.isJsonObject()) {
            if (offset != null || limit != null) {
                throw new IllegalArgumentException("路径 " + path + " 是一个" + typeWord(value) + "，整段给，不用 offset / limit");
            }
            page.add("value", value);
            return page;
        }
        int total = value.isJsonArray() ? value.getAsJsonArray().size() : value.getAsJsonObject().size();
        int from = offset == null ? 0 : offset;
        if (from > total) {
            throw new IllegalArgumentException("路径 " + path + " 一共 " + total + " 项，offset " + from + " 超出了");
        }
        JsonArray folded = new JsonArray();
        int end = value.isJsonArray()
                ? pageOfList(value.getAsJsonArray(), path, from, limit, page, folded)
                : pageOfObject(value.getAsJsonObject(), path, from, limit, page, folded);
        page.addProperty("total", total);
        page.addProperty("offset", from);
        if (end < total) page.addProperty("next_offset", end);
        if (!folded.isEmpty()) page.add("folded", folded);
        return page;
    }

    // 列表的一页：逐项装，项里再大的子块折起来；不给 limit 时装满一页的字数就停（至少一项）。返回读到第几项为止。
    private static int pageOfList(JsonArray list, String path, int from, Integer limit, JsonObject page,
                                  JsonArray folded) {
        JsonArray items = new JsonArray();
        int used = 0;
        int index = from;
        for (; index < list.size() && (limit == null || index - from < limit); index++) {
            JsonArray inner = new JsonArray();
            JsonElement item = compact(list.get(index), childPath(path, Integer.toString(index)), inner);
            int size = chars(item) + 1;
            if (limit == null && !items.isEmpty() && used + size > PAGE_CHARS) break;
            items.add(item);
            folded.addAll(inner);
            used += size;
        }
        page.add("value", items);
        return index;
    }

    // 对象的一页：按字段的先后翻，规矩同列表；大的列表、文字字段不放进这一页，路径列进 folded。
    private static int pageOfObject(JsonObject object, String path, int from, Integer limit, JsonObject page,
                                    JsonArray folded) {
        List<String> keys = new ArrayList<>(object.keySet());
        JsonObject fields = new JsonObject();
        int used = 0;
        int index = from;
        for (; index < keys.size() && (limit == null || index - from < limit); index++) {
            String key = keys.get(index);
            JsonElement child = object.get(key);
            if (chars(child) > FOLD_CHARS && !child.isJsonObject()) {
                folded.add(reference(childPath(path, key), child));
                continue;
            }
            JsonArray inner = new JsonArray();
            JsonElement shown = compact(child, childPath(path, key), inner);
            int size = chars(shown) + key.length() + 4;
            if (limit == null && !fields.isEmpty() && used + size > PAGE_CHARS) break;
            fields.add(key, shown);
            folded.addAll(inner);
            used += size;
        }
        page.add("value", fields);
        return index;
    }

    // 翻页时的一项：不大照原样；是个大对象就折起它里面大的子项；大的列表、文字整项换成一个指向它的占位，按路径再读。
    private static JsonElement compact(JsonElement value, String path, JsonArray folded) {
        if (chars(value) <= FOLD_CHARS) return value;
        if (value.isJsonObject()) return foldChildren(value.getAsJsonObject(), path, folded);
        folded.add(reference(path, value));
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("folded", path);
        return placeholder;
    }

    /** 折起来的一块：路径、形状、几项（列表与对象）、多少字；按路径读得回原样。 */
    private static JsonObject reference(String path, JsonElement value) {
        JsonObject json = new JsonObject();
        json.addProperty("path", path);
        json.addProperty("type", type(value));
        if (value.isJsonArray()) json.addProperty("items", value.getAsJsonArray().size());
        if (value.isJsonObject()) json.addProperty("items", value.getAsJsonObject().size());
        json.addProperty("chars", chars(value));
        return json;
    }

    // 按 JSON Pointer 找到那一段：/ 开头、一层一段，~1 是斜杠、~0 是波浪号，列表按下标。
    private static JsonElement resolve(JsonObject root, String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path 要以 / 开头，照抄 folded 里的 path，例如 /attempts");
        }
        JsonElement current = root;
        for (String raw : path.substring(1).split("/", -1)) {
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current.isJsonObject() && current.getAsJsonObject().has(key)) {
                current = current.getAsJsonObject().get(key);
            } else if (current.isJsonArray() && key.matches("0|[1-9][0-9]{0,8}")
                    && Integer.parseInt(key) < current.getAsJsonArray().size()) {
                current = current.getAsJsonArray().get(Integer.parseInt(key));
            } else {
                throw new IllegalArgumentException("结果里没有 " + path + " 这一段；结果的顶层有 "
                        + String.join("、", root.keySet()));
            }
        }
        return current;
    }

    static String childPath(String parent, String key) {
        return parent + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static boolean isEmpty(JsonElement value) {
        return value.isJsonArray() && value.getAsJsonArray().isEmpty()
                || value.isJsonObject() && value.getAsJsonObject().isEmpty();
    }

    private static String type(JsonElement value) {
        if (value.isJsonArray()) return "list";
        if (value.isJsonObject()) return "object";
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? "text" : "value";
    }

    private static String typeWord(JsonElement value) {
        return type(value).equals("text") ? "一句文字" : "值";
    }

    /** 这一块写成 JSON 有多少字。 */
    static int chars(JsonElement value) {
        return value.toString().length();
    }
}
