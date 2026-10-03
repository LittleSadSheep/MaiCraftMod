// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Need;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Recipe;

/** 现货在任意层终止展开；共享原料只算一次，整批余料复用，补料切点按取得成本选择。 */
public final class RecipeMaterialPlanTest {
    private static final ResourceLocation LOG = id("log"), PLANK = id("plank"), PART = id("part"), IRON = id("iron");
    public static void main(String[] args) {
        Map<ResourceLocation, List<Recipe>> recipes = Map.of(
                PLANK, List.of(new Recipe(4, List.of(need(LOG, 1)))),
                PART, List.of(new Recipe(1, List.of(need(PLANK, 2), need(IRON, 1)))));
        var held = plan(List.of(need(PART, 1)), Map.of(PART, 1L), recipes, Map.of());
        check(held.supplies().isEmpty() && held.crafts().isEmpty(), "held finished items stop the tree immediately");
        var partial = plan(List.of(need(PART, 1)), Map.of(LOG, 1L), recipes, Map.of());
        check(partial.supplies().equals(List.of(need(IRON, 1))) && partial.remaining().get(PLANK) == 2,
                "available logs remove the wood branch even when another ingredient is missing");
        var batch = plan(List.of(need(PLANK, 3), need(PLANK, 2)), Map.of(LOG, 1L), recipes, Map.of(LOG, 10));
        check(batch.supplies().equals(List.of(need(LOG, 1))), "two demands cannot both spend the same original log");
        var mid = plan(List.of(need(PART, 1)), Map.of(), recipes, Map.of(PART, 2, LOG, 20, IRON, 20));
        check(mid.supplies().equals(List.of(need(PART, 1))) && mid.crafts().isEmpty(), "a cheaper known intermediate acquisition can beat raw materials");
        var raw = plan(List.of(need(PLANK, 8)), Map.of(), recipes, Map.of(PLANK, 20, LOG, 5));
        check(raw.supplies().equals(List.of(need(LOG, 2))), "batch yield selects logs rather than eight separate planks");
        Map<ResourceLocation, List<Recipe>> cycle = Map.of(PLANK, List.of(new Recipe(1, List.of(need(LOG, 1)))),
                LOG, List.of(new Recipe(1, List.of(need(PLANK, 1)))));
        check(!plan(List.of(need(PLANK, 1)), Map.of(), cycle, Map.of()).feasible(), "closed conversion cycles cannot manufacture free resources");
        check(plan(List.of(need(IRON, 1)), Map.of(), Map.of(), Map.of()).cost() >= 10000, "missing recipe is an unknown material source, not zero cost");
        // 第一支可用铁或木，第二支只能用铁；不能因先取铁而错误要求用户再补一块铁。
        var coupled = Map.of(PLANK, List.of(new Recipe(1, List.of(new Need(List.of(IRON, LOG), 1)))),
                PART, List.of(new Recipe(1, List.of(need(IRON, 1)))));
        var shared = plan(List.of(need(PLANK, 1), need(PART, 1)), Map.of(IRON, 1L, LOG, 1L), coupled, Map.of());
        check(shared.feasible() && shared.supplies().isEmpty(), "alternative allocation must preserve the other branch's unique ingredient");
        knownRoutePrecedesWideUnknownBranches();
        woolRecoloringDoesNotHidePlainMaterialRoute();
        materialPreferencesReachDeepInputsAndFallBack();
        carriedAncestorMaterialsRemainUsable();
        System.out.println("RecipeMaterialPlanTest: passed");
    }

    private static void carriedAncestorMaterialsRemainUsable() {
        // 为继续采同一种原木而准备斧头时，原木虽在祖先链中，背包已有实物仍可加工成木板和木棍。
        var oak = id("oak_log"); var oakPlanks = id("oak_planks"); var stick = id("stick");
        var recipes = Map.of(PLANK, List.of(new Recipe(4, List.of(need(LOG, 1)))),
                oakPlanks, List.of(new Recipe(4, List.of(need(oak, 1)))),
                stick, List.of(new Recipe(4, List.of(new Need(List.of(oakPlanks, PLANK), 2)))));
        var stocked = RecipeMaterialPlan.estimate(List.of(need(stick, 2)), Map.of(LOG, 3L),
                item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(item.equals(LOG) || item.equals(oak), 40), Set.of(LOG));
        check(stocked.feasible() && stocked.supplies().isEmpty() && stocked.remaining().get(LOG) == 2,
                "采集祖先中的现有原木应先用于工具材料，不能被循环规则抹掉后另找木种");
        // 只允许扣掉确实存在的数量；库存不足时不能递归制造祖先物品来填补缺口。
        var shortStock = RecipeMaterialPlan.estimate(List.of(need(PLANK, 8)), Map.of(LOG, 1L),
                item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(item.equals(LOG) || item.equals(oak), 40), Set.of(LOG));
        check(!shortStock.feasible(), "不能把允许使用现货扩成允许循环补足祖先缺额");
        var absent = RecipeMaterialPlan.estimate(List.of(need(PLANK, 4)), Map.of(),
                item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(item.equals(LOG) || item.equals(oak), 40), Set.of(LOG));
        check(!absent.feasible(), "没有祖先现货时循环限制仍须生效");
        // 两条配件路线共享唯一一根原木，祖先现货不能因为允许使用而重复记账。
        var shared = RecipeMaterialPlan.estimate(List.of(need(PLANK, 4), need(PART, 1)), Map.of(LOG, 1L),
                item -> item.equals(PART) ? List.of(new Recipe(1, List.of(need(LOG, 1)))) : recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(false, 10000), Set.of(LOG));
        check(!shared.feasible(), "祖先现货仍按共享数量账扣除，只能消费一次");
    }

    private static void materialPreferencesReachDeepInputsAndFallBack() {
        // 同一部件可用便宜金属直接制作，也可经木板使用原木；模型指定原木时应看见深层依赖。
        var recipes = Map.of(PART, List.of(new Recipe(1, List.of(need(IRON, 1))), new Recipe(1, List.of(need(PLANK, 1)))),
                PLANK, List.of(new Recipe(4, List.of(need(LOG, 1)))));
        var preferred = RecipeMaterialPlan.estimate(List.of(need(PART, 1)), Map.of(),
                item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(item.equals(LOG) || item.equals(IRON), item.equals(LOG) ? 40 : 1),
                Set.of(), Set.of(), Set.of(LOG));
        check(preferred.supplies().equals(List.of(need(LOG, 1))) && preferred.preferredMaterialsUsed().equals(Set.of(LOG)),
                "深层原木倾向应优先于另一条缺料路线，并保留实际匹配证据");
        // 已携带金属足以完成目标时，不因软偏好再去野外采木。
        var stocked = RecipeMaterialPlan.estimate(List.of(need(PART, 1)), Map.of(IRON, 1L),
                item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(item.equals(LOG) || item.equals(IRON), 40),
                Set.of(), Set.of(), Set.of(LOG));
        check(stocked.supplies().isEmpty() && stocked.preferredMaterialsUsed().isEmpty(), "现货覆盖的完整路线保持优先");
        // 偏好原木的获取入口耗尽后仍可回退到金属路线，不把软偏好升级成材料白名单。
        var fallback = RecipeMaterialPlan.estimate(List.of(need(PART, 1)), Map.of(),
                item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(item.equals(LOG) || item.equals(IRON), 40),
                Set.of(), Set.of(LOG), Set.of(LOG));
        check(fallback.feasible() && fallback.supplies().equals(List.of(need(IRON, 1)))
                        && fallback.preferredMaterialsUsed().isEmpty(), "偏好路线失效后仍能选择原许可内的替代原料");
    }

    private static void woolRecoloringDoesNotHidePlainMaterialRoute() {
        // 风帆接受任意颜色羊毛；各色互染不能耗尽预算后，把十几种染料误列为必须先取的材料。
        var wool = new ArrayList<ResourceLocation>();
        for (int i = 0; i < 16; i++) wool.add(id("wool_" + i));
        var string = id("string");
        var recipes = new LinkedHashMap<ResourceLocation, List<Recipe>>();
        for (int i = 0; i < wool.size(); i++) {
            var choices = new ArrayList<Recipe>();
            choices.add(new Recipe(1, List.of(new Need(wool, 1), need(id("dye_" + i), 1))));
            if (i == 0) choices.add(new Recipe(1, List.of(need(string, 4))));
            recipes.put(wool.get(i), choices);
        }
        var required = new Need(wool, 5);
        var control = plan(List.of(need(wool.getFirst(), 5)), Map.of(),
                Map.of(wool.getFirst(), List.of(new Recipe(1, List.of(need(string, 4))))), Map.of());
        var replay = plan(List.of(required), Map.of(), recipes, Map.of());
        check(replay.feasible() && replay.cost() == control.cost()
                        && replay.supplies().equals(List.of(need(string, 20)))
                        && replay.crafts().equals(List.of(need(wool.getFirst(), 5))),
                "bounded wool recoloring search must retain the complete plain-string route");
        // 已有羊毛仍应直接扣账，不因新排序而强迫重新制作或染色。
        var held = plan(List.of(required), Map.of(wool.get(7), 5L), recipes, Map.of());
        check(held.feasible() && held.supplies().isEmpty() && held.crafts().isEmpty(),
                "carried wool satisfies any-color material without extra work");
        // 浅层已有昂贵整件来源时仍继续看原料路线，不能把首次可行误当成最便宜的完整方案。
        var deeper = plan(List.of(need(PART, 1)), Map.of(), Map.of(
                PART, List.of(new Recipe(1, List.of(need(PLANK, 2)))),
                PLANK, List.of(new Recipe(4, List.of(need(LOG, 1))))), Map.of(PART, 100, LOG, 1));
        check(deeper.supplies().equals(List.of(need(LOG, 1))) && deeper.cost() == 3,
                "later complete raw-material routes can improve a shallow direct-supply plan");
    }

    private static void knownRoutePrecedesWideUnknownBranches() {
        // 保留原8192步上限；数千条未知叶子排在前面时，最后一条已知便宜路线也必须先得到比较机会。
        var target = id("wide_target"); var known = id("known_leaf");
        var alternatives = new ArrayList<Recipe>();
        for (int i = 0; i < 4200; i++) alternatives.add(new Recipe(1, List.of(need(id("wide_leaf_" + i), 1))));
        var direct = new Recipe(1, List.of(need(known, 1))); alternatives.add(direct);
        var replay = plan(List.of(need(target, 1)), Map.of(), Map.of(target, alternatives), Map.of(known, 1));
        var control = plan(List.of(need(target, 1)), Map.of(), Map.of(target, List.of(direct)), Map.of(known, 1));
        check(replay.feasible() && replay.cost() == control.cost() && replay.supplies().equals(control.supplies()),
                "a wide unknown branch list cannot hide the later known cheap route");
        check(!replay.searchComplete(), "prioritizing useful routes does not pretend the bounded search was exhaustive");
        // 同一替代组也比较下一层实际可用材料，普通原木路径优先于没有来源的模组木板。
        var opaque = id("opaque_plank"); var planks = id("ordinary_planks");
        var mixed = plan(List.of(new Need(List.of(opaque, planks), 16)), Map.of(),
                Map.of(planks, List.of(new Recipe(4, List.of(need(LOG, 1))))), Map.of(LOG, 40));
        check(mixed.supplies().equals(List.of(need(LOG, 4))), "ordinary known timber outranks an unknown alternate plank");
    }

    private static RecipeMaterialPlan.Result plan(List<Need> needs, Map<ResourceLocation, Long> stock,
                                                  Map<ResourceLocation, List<Recipe>> recipes, Map<ResourceLocation, Integer> costs) {
        return RecipeMaterialPlan.estimate(needs, stock, item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(costs.containsKey(item), costs.getOrDefault(item, 10000)), Set.of());
    }
    private static Need need(ResourceLocation item, int count) { return new Need(List.of(item), count); }
    private static ResourceLocation id(String value) { return ResourceLocation.fromNamespaceAndPath("test", value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
