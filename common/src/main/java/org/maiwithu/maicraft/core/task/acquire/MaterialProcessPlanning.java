// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.integration.emi.EmiRecipeKnowledge;
import java.util.Locale;

/** 普通有限获取路线耗尽后冻结知识交接；不创建机器、不调用LLM，也不把EMI展示证据升级为实物操作许可。 */
public final class MaterialProcessPlanning {
    public static final String KIND = "material_process_planning";
    public static final String FAILURE_CODE = "material_process_planning_required";
    static final int MAX_BLOCKED_NEEDS = 4, MAX_QUERIES = 4;
    private static final String GUIDE = "maicraft://knowledge/recipes";
    private static final Set<String> QUERY_STATES = Set.of("not_installed", "not_loaded", "no_matches", "available",
            "partial", "api_unavailable", "query_item_unavailable");

    record NeedEvidence(List<ResourceLocation> itemIds, int required, int observed, int depth,
                        List<ResourceLocation> lineageItems, List<String> lineageRecipes,
                        List<String> parentRecipes, List<String> committedRecipes, boolean effectsObserved) {
        NeedEvidence {
            itemIds = List.copyOf(itemIds); lineageItems = List.copyOf(lineageItems);
            lineageRecipes = List.copyOf(lineageRecipes); parentRecipes = List.copyOf(parentRecipes);
            committedRecipes = List.copyOf(committedRecipes);
        }
        Map<String, Object> describe() {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("item_ids", itemIds.stream().map(ResourceLocation::toString).toList());
            facts.put("required_final_count", required); facts.put("observed_final_count", observed);
            facts.put("missing", Math.max(0, required - observed)); facts.put("depth", depth);
            facts.put("lineage_item_ids", lineageItems.stream().map(ResourceLocation::toString).toList());
            facts.put("lineage_recipe_ids", lineageRecipes); facts.put("parent_recipe_ids", parentRecipes);
            facts.put("committed_recipe_ids", committedRecipes); facts.put("effects_observed", effectsObserved);
            return Map.copyOf(facts);
        }
    }

    private MaterialProcessPlanning() {}

    static boolean allowsPlanning(List<SemanticAcquireTaskRecord.Source> sources) {
        // 只授权查库存或拿现货的请求仍保留原失败含义；不能借知识交接偷偷追加制造路线。
        return sources.contains(SemanticAcquireTaskRecord.Source.CRAFT) || sources.contains(SemanticAcquireTaskRecord.Source.COOK);
    }

    static Map<String, Object> capture(LocalPlayer player, SemanticAcquireTaskRecord request, int finalObserved,
                                       NeedEvidence blocked, List<NeedEvidence> candidates, boolean effectsObserved, String cause) {
        return capture(player, request, finalObserved, blocked, candidates, effectsObserved, cause,
                (body, item) -> EmiRecipeKnowledge.probe(body, item, false));
    }

    static Map<String, Object> capture(LocalPlayer player, SemanticAcquireTaskRecord request, int finalObserved,
                                       NeedEvidence blocked, List<NeedEvidence> candidates, boolean effectsObserved, String cause,
                                       BiFunction<LocalPlayer, ResourceLocation, JsonObject> inspect) {
        var needs = new LinkedHashSet<NeedEvidence>(); needs.add(blocked); needs.addAll(candidates);
        List<NeedEvidence> limited = needs.stream().limit(MAX_BLOCKED_NEEDS).toList();
        var items = new LinkedHashSet<ResourceLocation>(); limited.forEach(need -> items.addAll(need.itemIds()));
        var queries = new ArrayList<Map<String, Object>>(); var links = new ArrayList<String>(); links.add(GUIDE);
        // 每次终止只探查至多四个物品的索引状态，不展开正文；外部规划者通过知识URI显式读取和翻页。
        for (ResourceLocation item : items.stream().limit(MAX_QUERIES).toList()) {
            String uri = recipeUri(item); links.add(uri); String status = "api_unavailable";
            try {
                JsonObject page = inspect.apply(player, item);
                if (page != null && page.has("status") && page.get("status").isJsonPrimitive()) {
                    String reported = page.get("status").getAsString();
                    if (QUERY_STATES.contains(reported)) status = reported;
                }
            } catch (RuntimeException | LinkageError unavailable) { /* 可选知识不可读时仍交回事实，不推断游戏里不存在配方。 */ }
            queries.add(Map.of("item_id", item.toString(), "source", "emi", "status", status,
                    "direction", "output", "knowledge_only", true, "knowledge_uri", uri));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", KIND); result.put("ordinary_route_failure_code", cause); result.put("knowledge_only", true);
        result.put("blocked_need", blocked.describe());
        result.put("blocked_need_candidates", limited.stream().map(NeedEvidence::describe).toList());
        result.put("final_inventory_goal", Map.of("item_ids", request.itemIds.stream().map(ResourceLocation::toString).toList(),
                "required_final_count", request.count, "observed_final_count", finalObserved,
                "missing", Math.max(0, request.count - finalObserved)));
        result.put("allowed_sources", request.allowedSources.stream().map(source -> source.name().toLowerCase(Locale.ROOT)).toList());
        result.put("effects_observed", effectsObserved); result.put("prior_attempts_field", "attempts");
        result.put("recipe_lineage_field", "recipe_trace"); result.put("knowledge_uris", List.copyOf(links));
        result.put("recipe_query_evidence", List.copyOf(queries)); result.put("unqueried_item_count", Math.max(0, items.size() - queries.size()));
        result.put("absence_is_not_recipe_proof", true); result.put("machine_route_established", false);
        // 缺料交接要说明格子合成的边界；外部规划者不能把已读到的打磨、充能等配方再次原样交给craft。
        result.put("ordinary_crafting_scope", "Inventory/crafting-table grids using carried materials. Other recipe types require an independently selected native item, block or machine operation; recipe visibility alone does not make craft executable.");
        result.put("execution_authorization", "unchanged; EMI knowledge does not grant allow_use, construction, materials, harm or protection permissions");
        result.put("next_steps", List.of("discover_recipe_and_requirements", "inspect_and_reuse_compatible_equipment",
                "review_and_build_only_if_needed_and_authorized", "operate_the_verified_process", "verify_final_main_inventory"));
        result.put("recovery", Map.of("choice", "recover", "goal_field", "details.goal", "sequence_ability", "maicraft:sequence",
                "resume", "Reassess the unchanged final inventory goal after authorized prerequisites complete."));
        result.put("retry_guidance", "Unchanged exhausted routes need new knowledge or a prerequisite; changed inventory or semantic parameters may be safely re-evaluated.");
        return Map.copyOf(result);
    }

    static String recipeUri(ResourceLocation item) {
        return GUIDE + "/" + item.getNamespace() + "/" + item.getPath() + "?direction=output&offset=0&limit=8";
    }
}
