// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

/** 搜索只召回目录身份；真实注册表也必须走模糊候选链，不能先用精确过滤丢掉错字目标。 */
public final class KnowledgeSearchTest {
    public static void main(String[] args) throws Exception {
        int[] reads = {0};
        var exact = entry("demo:precision_part", "精密构建");
        var nearby = entry("demo:precision_mechanism", "精密构件");
        var library = new KnowledgeLibrary(new KnowledgeLibrary.Source() {
            public List<KnowledgeDocument.Entry> entries() { return List.of(nearby, exact); }
            public KnowledgeDocument read(String uri) {
                reads[0]++; return new KnowledgeDocument(uri, "selected", "已选条目", "完整资料", "# 原文");
            }
        });
        JsonObject search = KnowledgeLibraryTest.request("search"); search.addProperty("query", "精密构建");
        search.addProperty("approximate", true); search.addProperty("limit", 1);
        var hits = library.request(search);
        var first = hits.getAsJsonArray("resources").get(0).getAsJsonObject();
        check(first.get("subject_id").getAsString().equals("demo:precision_part"), "exact identity first");
        check(hits.get("total_matches").getAsInt() == 2 && hits.get("truncated").getAsBoolean(), "bounded candidates remain explicit");
        check(!hits.get("content_loaded").getAsBoolean() && reads[0] == 0 && !first.has("text"), "no recipe or document reads");
        search.addProperty("query", "precision_mechanim"); search.addProperty("limit", 5);
        var approximate = library.request(search).getAsJsonArray("resources").get(0).getAsJsonObject();
        check(approximate.get("uri").getAsString().equals(nearby.uri())
                && approximate.getAsJsonObject("match").get("kind").getAsString().equals("approximate"), "identifier typo match");
        library.read(approximate.get("uri").getAsString()); check(reads[0] == 1, "only selected body is read");
        verifyRegistryRecall();
        verifyOriginalNameRanking();
        System.out.println("KnowledgeSearchTest: passed");
    }

    private static void verifyRegistryRecall() throws Exception {
        // 独立 JVM 中给原版物品注入测试译名，直接验证生产注册表路径，退出时恢复全局语言对象。
        var field = I18n.class.getDeclaredField("language"); field.setAccessible(true);
        Object previous = field.get(null);
        var constructor = ClientLanguage.class.getDeclaredConstructor(Map.class, boolean.class); constructor.setAccessible(true);
        try {
            field.set(null, constructor.newInstance(Map.of("item.minecraft.paper", "铜质齿轮"), false));
            var candidates = new MinecraftKnowledgeSource().searchCandidates("铜质齿伦", true);
            check(candidates.stream().anyMatch(entry -> entry.uri().equals("maicraft://knowledge/recipes/minecraft/paper")
                    && entry.subjectId().equals("minecraft:paper")), "real registry candidate survives typo recall");
        } finally { field.set(null, previous); }
    }

    private static void verifyOriginalNameRanking() {
        // 配方标题自带后缀，不能因此让“齿轮机”与“齿轮”的精确名称命中混成同一等级。
        var exact = RecipeKnowledgeSource.entry(ResourceLocation.parse("demo:z_exact"), "铜质齿轮");
        var longer = RecipeKnowledgeSource.entry(ResourceLocation.parse("demo:a_longer"), "铜质齿轮机");
        var library = new KnowledgeLibrary(new KnowledgeLibrary.Source() {
            public List<KnowledgeDocument.Entry> entries() { return List.of(longer, exact); }
            public KnowledgeDocument read(String uri) { throw new AssertionError("search must not read recipes"); }
        });
        var query = KnowledgeLibraryTest.request("search"); query.addProperty("approximate", true); query.addProperty("query", "铜质齿轮");
        var first = library.request(query).getAsJsonArray("resources").get(0).getAsJsonObject();
        check(first.get("subject_id").getAsString().equals("demo:z_exact")
                && first.getAsJsonObject("match").get("kind").getAsString().equals("exact"), "registered name ranks independently of document title");
    }

    private static KnowledgeDocument.Entry entry(String id, String name) {
        return new KnowledgeDocument.Entry("maicraft://knowledge/test/" + id, id, name, "目录描述", "", "application/json", id);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
