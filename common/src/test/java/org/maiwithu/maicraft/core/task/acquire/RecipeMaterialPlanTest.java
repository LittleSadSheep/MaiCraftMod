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
        System.out.println("RecipeMaterialPlanTest: passed");
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
