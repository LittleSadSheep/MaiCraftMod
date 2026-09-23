// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 只比较目录元数据的关键词与近似名称，不读取配方、教程正文或游戏世界。 */
public final class MetadataSearch {
    private MetadataSearch() {}

    public record Match(int score, String kind, String field, int edits) {
        /** 报告匹配依据而非概率，调用者须选定真实条目后再读资料或请求操作。 */
        public JsonObject toJson() {
            var result = new JsonObject();
            result.addProperty("kind", kind); result.addProperty("field", field);
            result.addProperty("ranking_score", score); result.addProperty("edit_distance", edits);
            return result;
        }
    }

    public static final class Query {
        private final String normalized;
        private final List<String> terms;

        public Query(String text) {
            if (text == null || text.isBlank() || text.length() > 256)
                throw new IllegalArgumentException("query must contain 1..256 characters of search keywords");
            normalized = normalize(text);
            terms = List.of(normalized.split("\\s+"));
        }

        /** 先精确标识与名称，再按词匹配；仅名称和标识接受有限错字，描述正文不做模糊扫描。 */
        public Match match(String identity, String title, String searchable) {
            String id = normalize(identity), name = normalize(title), metadata = normalize(searchable);
            if (id.equals(normalized)) return new Match(1000, "exact", "identifier", 0);
            if (name.equals(normalized)) return new Match(990, "exact", "name", 0);
            if (id.contains(normalized)) return new Match(950, "substring", "identifier", 0);
            if (name.contains(normalized)) return new Match(900, "substring", "name", 0);
            int score = 800, totalEdits = 0;
            String field = "metadata";
            for (String term : terms) {
                if (id.contains(term) || name.contains(term)) continue;
                if (metadata.contains(term)) { score = Math.min(score, 700); continue; }
                int length = term.codePointCount(0, term.length());
                // 短词要求字面证据；长句应由调用者提炼关键词，避免把任意段落误判成近似名称。
                if (length < 3 || length > 48) return null;
                int budget = length >= 8 ? 2 : 1;
                int idEdits = distance(term, id, budget), nameEdits = distance(term, name, budget);
                int edits = Math.min(idEdits, nameEdits);
                if (edits > budget) return null;
                totalEdits += edits;
                field = nameEdits <= idEdits ? "name" : "identifier";
                score = Math.min(score, 600 - edits * 60);
            }
            return new Match(score, totalEdits == 0 ? "keywords" : "approximate", field, totalEdits);
        }
    }

    /** 全角、大小写和分隔符差异不改变名称身份，空格仅用于拆分检索词。 */
    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private static int distance(String needle, String haystack, int budget) {
        int[] query = needle.codePoints().toArray(), text = haystack.codePoints().toArray();
        // 对名称做子串编辑距离，前后可有修饰语；上限控制客户端线程的匹配工作量。
        if (text.length == 0 || text.length > 256) return budget + 1;
        // 注册表里的绝大多数名称完全无关，缺失字符已超额度时直接排除，不分配编辑距离表。
        int missing = 0;
        for (int character : query) {
            boolean found = false;
            for (int candidate : text) if (character == candidate) { found = true; break; }
            if (!found && ++missing > budget) return budget + 1;
        }
        int[] previous = new int[text.length + 1], older = previous.clone();
        for (int i = 1; i <= query.length; i++) {
            int[] current = new int[text.length + 1]; current[0] = i;
            for (int j = 1; j <= text.length; j++) {
                current[j] = Math.min(previous[j] + 1, Math.min(current[j - 1] + 1,
                        previous[j - 1] + (query[i - 1] == text[j - 1] ? 0 : 1)));
                if (i > 1 && j > 1 && query[i - 1] == text[j - 2] && query[i - 2] == text[j - 1])
                    current[j] = Math.min(current[j], older[j - 2] + 1);
            }
            older = previous; previous = current;
        }
        int best = budget + 1;
        for (int value : previous) best = Math.min(best, value);
        return best;
    }

    /** 合并候选时统一按匹配度、稳定标识排序；只截取候选页，不提前展开命中对象的完整资料。 */
    public static List<JsonObject> ranked(List<JsonObject> rows, String identityField) {
        var sorted = new ArrayList<>(rows);
        sorted.sort((left, right) -> {
            int score = Integer.compare(right.getAsJsonObject("match").get("ranking_score").getAsInt(),
                    left.getAsJsonObject("match").get("ranking_score").getAsInt());
            return score != 0 ? score : left.get(identityField).getAsString().compareTo(right.get(identityField).getAsString());
        });
        return List.copyOf(sorted);
    }
}
