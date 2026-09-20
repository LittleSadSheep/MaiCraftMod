// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;

/** 用有界假索引验证只读分页契约；不会模拟EMI引擎，也不启动游戏或创建合成动作。 */
public final class EmiRecipeKnowledgeTest {
    private static final ResourceLocation ITEM = ResourceLocation.parse("minecraft:book");
    public static void main(String[] args) {
        statusBoundaries(); metadataProbe(); lazyPages(); invalidatedAndBrokenRows(); descriptionLimits();
        EmiRecipeReaderTest.main(args);
        System.out.println("EmiRecipeKnowledgeTest: optional presence, bounded lazy metadata and reader contracts passed");
    }

    private static void statusBoundaries() {
        var absent = new ReflectiveEmiRecipeAccess(new ClassLoader(null) {}).query(null, ITEM, false);
        check(absent.status().equals("not_installed"), "没有EMI时可选适配不能链接失败或冒称无配方");
        for (String status : List.of("not_installed", "not_loaded", "api_unavailable")) {
            var reader = new EmiRecipeKnowledge((player, id, uses) -> EmiRecipeAccess.Query.unavailable(status, "fixture status"));
            JsonObject response = reader.read(null, ITEM, false, 0, 8);
            check(response.get("status").getAsString().equals(status) && !response.has("total_matches"), "未获得索引不能补造零匹配");
        }
        var empty = new EmiRecipeKnowledge((player, id, uses) -> new EmiRecipeAccess.Query("available", "loaded", "test", 0,
                index -> { throw new AssertionError("空索引不能读取正文"); }, () -> true)).read(null, ITEM, true, 0, 8);
        check(empty.get("status").getAsString().equals("no_matches") && empty.get("absence_is_not_recipe_proof").getAsBoolean(),
                "已加载但无展示匹配与未加载区分，仍不推断游戏不存在配方");
    }

    private static void lazyPages() {
        List<Integer> reads = new ArrayList<>(); var direction = new AtomicBoolean(); JsonObject shared = recipe("shared");
        var reader = new EmiRecipeKnowledge((player, id, uses) -> {
            check(id.equals(ITEM), "查询仍绑定实际物品ID"); direction.set(uses);
            return new EmiRecipeAccess.Query("available", "fixture index", "catalog-a", 100, index -> { reads.add(index); return shared; }, () -> true);
        });
        var page = reader.read(null, ITEM, true, 7, 3);
        check(reads.equals(List.of(7, 8, 9)) && direction.get(), "只展开指定用途页，不提前展开全部索引或配方树");
        check(page.get("total_matches").getAsInt() == 100 && page.get("returned").getAsInt() == 3
                && page.get("next_offset").getAsInt() == 10 && page.get("truncated").getAsBoolean(), "分页保留总数与准确下一游标");
        check(!shared.has("index") && page.getAsJsonArray("display_recipes").get(0).getAsJsonObject().get("knowledge_only").getAsBoolean(),
                "返回正文副本，不能改EMI或把展示声明升级为执行授权");
        reads.clear(); var exhausted = reader.read(null, ITEM, false, 200, 4);
        check(reads.isEmpty() && exhausted.get("returned").getAsInt() == 0 && !exhausted.has("next_offset"), "超过结果范围不会重读最后一页");
        rejects(() -> reader.read(null, ITEM, false, -1, 1)); rejects(() -> reader.read(null, ITEM, false, 4097, 1));
        rejects(() -> reader.read(null, ITEM, false, 0, 0)); rejects(() -> reader.read(null, ITEM, false, 0, 17));
    }

    private static void metadataProbe() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var reader = new EmiRecipeKnowledge((player, id, uses) -> new EmiRecipeAccess.Query("available", "fixture", "metadata", 1200,
                index -> { calls.incrementAndGet(); throw new AssertionError("索引提示不能展开任何正文"); }, () -> true));
        JsonObject probe = reader.readIndex(null, ITEM, false);
        check(calls.get() == 0 && probe.get("metadata_only").getAsBoolean() && probe.get("total_matches").getAsInt() == 1200
                && probe.getAsJsonArray("display_recipes").isEmpty(), "取材失败交接只查索引，正文必须显式按需读取");
    }

    private static void invalidatedAndBrokenRows() {
        var current = new AtomicBoolean(true);
        var changing = new EmiRecipeKnowledge((player, id, uses) -> new EmiRecipeAccess.Query("available", "fixture", "old", 1,
                index -> { current.set(false); return recipe("old"); }, current::get));
        var changed = changing.read(null, ITEM, false, 0, 1);
        check(changed.get("status").getAsString().equals("not_loaded") && changed.getAsJsonArray("display_recipes").isEmpty(),
                "读取期间重载时丢弃旧管理器正文，不能混淆新配方");
        var partial = new EmiRecipeKnowledge((player, id, uses) -> new EmiRecipeAccess.Query("available", "fixture", "same", 2,
                index -> { if (index == 0) throw new IllegalStateException("bad plugin metadata"); return recipe("second"); }, () -> true))
                .read(null, ITEM, false, 0, 2);
        check(partial.get("status").getAsString().equals("partial") && partial.getAsJsonArray("display_recipes").size() == 2,
                "一条坏展示元数据应标明不可读，保留同页其他配方而非误报无匹配");
    }

    private static void descriptionLimits() {
        var huge = recipe("oversized"); huge.addProperty("fixture_text", "界".repeat(30_000));
        var response = new EmiRecipeKnowledge((player, id, uses) -> new EmiRecipeAccess.Query("available", "fixture", "a", 1,
                index -> huge, () -> true)).read(null, ITEM, false, 0, 1);
        check(response.getAsJsonArray("display_recipes").get(0).getAsJsonObject().get("issue").getAsString().contains("budget"),
                "过大正文以明确缺失代替，不截半份组件后伪装完整");
        var large = recipe("large"); large.addProperty("fixture_text", "x".repeat(50_000));
        var paged = new EmiRecipeKnowledge((player, id, uses) -> new EmiRecipeAccess.Query("available", "fixture", "a", 32,
                index -> large, () -> true)).read(null, ITEM, false, 0, 16);
        check(EmiRecipeKnowledge.encodedBytes(paged) <= EmiRecipeKnowledge.MAX_PAGE_BYTES
                && paged.get("next_offset").getAsInt() == paged.get("returned").getAsInt(), "正文预算提前分页且不跳过未返回条目");
    }
    private static JsonObject recipe(String id) {
        JsonObject out = new JsonObject(); out.addProperty("display_recipe_id", "example:" + id); out.addProperty("details_complete", true); return out;
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("invalid page accepted");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
