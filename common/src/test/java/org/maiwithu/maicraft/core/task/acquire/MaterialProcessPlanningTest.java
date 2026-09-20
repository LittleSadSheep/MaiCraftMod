// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 工艺知识交接只冻结状态与链接；可选索引缺失不变成无配方结论，也不改变任何库存或建造许可。 */
public final class MaterialProcessPlanningTest {
    private static final ResourceLocation FINAL = id("diamond"), BLOCKED = id("quartz");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var request = request(); var need = evidence(List.of(BLOCKED), 1);
            for (String state : List.of("not_installed", "not_loaded", "no_matches", "available", "partial", "api_unavailable", "query_item_unavailable")) {
                JsonObject page = new JsonObject(); page.addProperty("status", state);
                JsonArray text = new JsonArray(); text.add("recipe_body_must_not_be_copied"); page.add("display_recipes", text);
                var facts = MaterialProcessPlanning.capture(world.player, request, 1, need, List.of(need), true,
                        "allowed_sources_exhausted", (player, item) -> page);
                var query = queries(facts).getFirst();
                check(query.get("status").equals(state) && query.get("knowledge_only").equals(true), "保留实际索引状态而非推断制造机制");
                check(!query.containsKey("total_matches") && !query.containsKey("match_count"), "缺EMI或未加载时不能补造零配方事实");
                check(facts.get("absence_is_not_recipe_proof").equals(true) && facts.get("machine_route_established").equals(false),
                        "任何展示状态都不直接证明无配方或已有可执行机器路线");
                check(!new Gson().toJson(facts).contains("recipe_body_must_not_be_copied"), "任务回执不膨胀为配方正文");
                page.addProperty("status", "changed_later");
                check(query.get("status").equals(state), "后来的EMI对象变化不能改写已经冻结的查询证据");
                check(facts.get("execution_authorization").toString().contains("unchanged"), "知识读取不授予额外游戏操作权限");
            }
            var failed = MaterialProcessPlanning.capture(world.player, request, 1, need, List.of(), false,
                    "allowed_sources_exhausted", (player, item) -> { throw new IllegalStateException("optional API unavailable"); });
            check(queries(failed).getFirst().get("status").equals("api_unavailable"), "可选知识异常仍以未知知识交回，不谎称无配方");
            boundedQueries(world, request);
            check(world.itemUses() == 0 && world.blockUses() == 0 && world.inventory.isEmpty(), "交接不能消耗材料、放机器或操作世界");
        }
        check(!MaterialProcessPlanning.allowsPlanning(List.of(SemanticAcquireTaskRecord.Source.INVENTORY)), "只查库存的许可不能被升级为制造规划");
        check(java.util.Arrays.stream(SemanticAcquireTaskRecord.Source.values()).noneMatch(source -> source.name().equals("MACHINE")),
                "工艺发现不需要增加一个默认授予危险动作的Source");
        System.out.println("MaterialProcessPlanningTest: bounded knowledge-only statuses, URIs and unchanged permissions passed");
    }

    private static void boundedQueries(InteractionWorldTestHarness world, SemanticAcquireTaskRecord request) {
        var blocked = evidence(List.of(BLOCKED, id("redstone"), id("lapis_lazuli"), id("coal"), id("iron_ingot")), 1);
        var alternatives = new ArrayList<MaterialProcessPlanning.NeedEvidence>();
        for (int depth = 1; depth <= 9; depth++) alternatives.add(evidence(List.of(BLOCKED), depth));
        int[] reads = {0};
        var facts = MaterialProcessPlanning.capture(world.player, request, 1, blocked, alternatives, true,
                "committed_prerequisite_unmet", (player, item) -> {
                    reads[0]++; JsonObject page = new JsonObject(); page.addProperty("status", "available"); return page;
                });
        check(reads[0] == 4 && queries(facts).size() == 4 && ((List<?>) facts.get("blocked_need_candidates")).size() == 4,
                "大标签和多个失败配方叶子都必须有界，不在终止刻展开整个配方图");
        check(facts.get("unqueried_item_count").equals(1), "诚实报告尚未查询的物品数");
        check(((List<?>) facts.get("knowledge_uris")).contains("maicraft://knowledge/recipes/minecraft/quartz?direction=output&offset=0&limit=8"),
                "知识链接按物品完整命名空间指向按需分页入口");
        check(((Map<?, ?>) facts.get("final_inventory_goal")).get("required_final_count").equals(2)
                        && ((Map<?, ?>) facts.get("blocked_need")).get("required_final_count").equals(3),
                "子材料缺口不能替换原请求的最终库存目标");
        check(((Map<?, ?>) facts.get("blocked_need")).get("parent_recipe_ids").equals(List.of("test:component_recipe"))
                        && facts.get("effects_observed").equals(true), "配方来路与已发生效果必须一起保留给外部恢复");
        check(!facts.containsKey("outcome_uncertain") && !facts.containsKey("mechanical_retry_allowed"),
                "普通知识缺口不冒用不确定消费标记，也不封死安全库存重查");
    }

    private static SemanticAcquireTaskRecord request() {
        return new SemanticAcquireTaskRecord("material-knowledge", 1000, List.of(FINAL), 2,
                List.of(SemanticAcquireTaskRecord.Source.CRAFT), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
    }
    private static MaterialProcessPlanning.NeedEvidence evidence(List<ResourceLocation> items, int depth) {
        return new MaterialProcessPlanning.NeedEvidence(items, 3, 0, depth, List.of(FINAL, BLOCKED),
                List.of("test:component_recipe"), List.of("test:component_recipe"), List.of(), true);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> queries(Map<String, Object> facts) {
        return (List<Map<String, Object>>) facts.get("recipe_query_evidence");
    }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
