// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** 按物品读取EMI来源或用途知识；展示证据不授权普通合成、机器操作或配方填充。 */
public final class EmiRecipeKnowledge {
    public static final int MAX_OFFSET = 4096, MAX_LIMIT = 16;
    static final int MAX_RECIPE_BYTES = 65_536, MAX_PAGE_BYTES = 262_144;
    private final EmiRecipeAccess access;

    public EmiRecipeKnowledge(EmiRecipeAccess access) { this.access = Objects.requireNonNull(access); }

    /** 调用方在当前客户端游戏线程读取；此入口不打开EMI界面、不调用addWidgets或craftRecipe。 */
    public static JsonObject inspect(LocalPlayer player, ResourceLocation itemId, boolean uses, int offset, int limit) {
        return new EmiRecipeKnowledge(new ReflectiveEmiRecipeAccess()).read(player, itemId, uses, offset, limit);
    }

    /** 取材路线耗尽时只提示是否有展示索引；正文等待LLM显式读取知识URI，不在终止回执里偷偷展开配方。 */
    public static JsonObject probe(LocalPlayer player, ResourceLocation itemId, boolean uses) {
        return new EmiRecipeKnowledge(new ReflectiveEmiRecipeAccess()).readIndex(player, itemId, uses);
    }

    public JsonObject readIndex(LocalPlayer player, ResourceLocation itemId, boolean uses) {
        if (itemId == null || itemId.toString().length() > 256) throw new IllegalArgumentException("EMI knowledge requires a bounded item_id");
        JsonObject out = envelope(itemId, uses, 0, 0); out.addProperty("metadata_only", true);
        out.addProperty("scope", "EMI index metadata only; no recipe descriptions were read");
        try {
            EmiRecipeAccess.Query query = access.query(player, itemId, uses);
            if (!"available".equals(query.status())) return unavailable(out, query.status(), query.detail());
            if (!current(query)) return unavailable(out, "not_loaded", "EMI catalog changed; inspect again");
            if (query.total() < 0) return unavailable(out, "api_unavailable", "EMI returned an invalid index count");
            out.addProperty("catalog_revision", query.revision()); out.addProperty("total_matches", query.total());
            return unavailable(out, query.total() == 0 ? "no_matches" : "available", query.total() == 0
                    ? "No matching EMI display entries; this does not prove that the game has no recipe" : query.detail());
        } catch (RuntimeException | LinkageError unavailable) { return unavailable(out, "api_unavailable", "EMI query could not be read"); }
    }

    public JsonObject read(LocalPlayer player, ResourceLocation itemId, boolean uses, int offset, int limit) {
        if (itemId == null || itemId.toString().length() > 256 || offset < 0 || offset > MAX_OFFSET || limit < 1 || limit > MAX_LIMIT)
            throw new IllegalArgumentException("EMI knowledge requires item_id, offset 0..4096 and limit 1..16");
        JsonObject out = envelope(itemId, uses, offset, limit);
        EmiRecipeAccess.Query query;
        try { query = access.query(player, itemId, uses); }
        catch (RuntimeException | LinkageError failure) { return unavailable(out, "api_unavailable", "EMI query could not be read"); }
        if (!"available".equals(query.status())) return unavailable(out, query.status(), query.detail());
        if (query.total() < 0) return unavailable(out, "api_unavailable", "EMI returned an invalid index count");
        if (!current(query)) return unavailable(out, "not_loaded", "EMI catalog changed; inspect again from offset 0");
        out.addProperty("catalog_revision", query.revision()); out.addProperty("total_matches", query.total());
        JsonArray rows = new JsonArray(); int next = Math.min(offset, query.total()), bytes = 0; boolean partial = false;
        for (; next < query.total() && next - offset < limit; next++) {
            JsonObject row;
            try {
                row = query.reader().apply(next).deepCopy();
                if (encodedBytes(row) > MAX_RECIPE_BYTES) row = unreadable("recipe_exceeds_description_budget");
            } catch (RuntimeException | LinkageError failure) { row = unreadable("recipe_metadata_unreadable"); }
            row.addProperty("index", next); row.addProperty("knowledge_only", true);
            row.addProperty("execution_support", "not_inferred_from_emi");
            int size = encodedBytes(row);
            if (bytes + size > MAX_PAGE_BYTES - 4096) break;
            bytes += size; rows.add(row);
            partial |= row.has("details_complete") && !row.get("details_complete").getAsBoolean();
        }
        // 只观察一份完整索引；重载发生时舍弃本页，不把已失效正文当成新管理器的配方。
        if (!current(query)) return unavailable(envelope(itemId, uses, offset, limit), "not_loaded", "EMI catalog changed; inspect again from offset 0");
        out.add("display_recipes", rows); out.addProperty("returned", rows.size());
        boolean more = next < query.total(); out.addProperty("truncated", more);
        if (more && next <= MAX_OFFSET) out.addProperty("next_offset", next);
        if (more && next > MAX_OFFSET) out.addProperty("pagination_limit_reached", true);
        out.addProperty("status", query.total() == 0 ? "no_matches" : partial ? "partial" : "available");
        out.addProperty("detail", query.total() == 0 ? "No matching EMI display entries; this does not prove that the game has no recipe" : query.detail());
        return out;
    }

    private static JsonObject envelope(ResourceLocation itemId, boolean uses, int offset, int limit) {
        JsonObject out = new JsonObject(); out.addProperty("source", "emi"); out.addProperty("item_id", itemId.toString());
        out.addProperty("direction", uses ? "uses" : "recipes"); out.addProperty("offset", offset); out.addProperty("limit", limit);
        out.addProperty("knowledge_only", true); out.addProperty("execution_support", "not_inferred_from_emi");
        out.addProperty("query_identity", "default_item_stack"); out.addProperty("absence_is_not_recipe_proof", true);
        out.addProperty("scope", "One EMI index page; input uses include catalysts. Workstations are category metadata. No dependency tree or widget-only conditions are inferred.");
        out.add("display_recipes", new JsonArray()); return out;
    }
    private static JsonObject unavailable(JsonObject out, String status, String detail) {
        out.addProperty("status", status); out.addProperty("detail", detail); return out;
    }
    private static JsonObject unreadable(String issue) {
        JsonObject out = new JsonObject(); out.addProperty("status", "unreadable");
        out.addProperty("details_complete", false); out.addProperty("issue", issue); return out;
    }
    private static boolean current(EmiRecipeAccess.Query query) {
        try { return query.current().getAsBoolean(); } catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    static int encodedBytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
}
