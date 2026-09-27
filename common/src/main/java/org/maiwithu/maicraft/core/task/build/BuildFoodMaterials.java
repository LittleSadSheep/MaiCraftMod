// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.inventory.FoodMaterialBudget;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/** 补食前读取整份施工及尚未执行的语义步骤；未选定路线的食物原料先整类保留，不能擅自消耗工序库存。 */
final class BuildFoodMaterials {
    private BuildFoodMaterials() {}
    static FoodMaterialBudget inspect(LocalPlayer player, BuildTaskRecord owner) {
        return inspect(player, owner, CompanionTickDispatcher.current());
    }
    // 子施工可能只是为当前配方摆工作台；预留必须同时包含外层当前目标，不能把马上要合成的海带当口粮。
    static FoodMaterialBudget inspect(LocalPlayer player, BuildTaskRecord owner, TaskRecord active) {
        var exact = new HashMap<Item, Long>(); var future = new HashSet<>(owner.futureWorkItems());
        for (var target : owner.targets) if (target.materialCount() > 0
                && (!player.level().isLoaded(target.pos()) || !target.matches(player.level().getBlockState(target.pos()))))
            exact.merge(target.item(), (long) target.materialCount(), Long::sum);
        if (active instanceof IntentTaskRecord intent) {
            // 当前和后续步骤都只读取已声明目标；消费食物的步骤仍由 referenced 排除，不保留模型尚未规划的需求。
            for (int i = intent.stepIndex(); i < intent.steps().size(); i++) referenced(intent.steps().get(i), future);
        }
        if (exact.isEmpty() && future.isEmpty()) return FoodMaterialBudget.EMPTY;
        try {
            var recipes = new HashMap<Item, Set<Item>>();
            for (var holder : ClientRuntime.requireContext(player).connection().getRecipeManager().getRecipes()) {
                var recipe = holder.value(); var output = recipe.getResultItem(player.level().registryAccess());
                if (output.isEmpty()) continue;
                var inputs = recipes.computeIfAbsent(output.getItem(), ignored -> new HashSet<>());
                for (var ingredient : recipe.getIngredients()) for (var stack : ingredient.getItems())
                    if (!stack.isEmpty()) inputs.add(stack.getItem());
            }
            return FoodMaterialBudget.plan(exact, future, item -> recipes.getOrDefault(item, Set.of()));
        } catch (RuntimeException unavailable) {
            // 配方观察不可用时保留食物，不把“还没查到用途”当成“没有用途”。
            return new FoodMaterialBudget(exact, future, false);
        }
    }
    private static void referenced(Goal goal, Set<Item> items) {
        if (!"maicraft:consume".equals(goal.ability())) {
            referenced(goal.parameters(), items);
            if (goal.target() != null && "item".equals(goal.target().kind())) add(goal.target().label(), items);
        }
        goal.children().forEach(child -> referenced(child, items));
    }
    private static void referenced(JsonElement value, Set<Item> items) {
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> referenced(entry.getValue(), items));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(entry -> referenced(entry, items));
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) add(value.getAsString(), items);
    }
    private static void add(String value, Set<Item> items) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) != Items.AIR)
            items.add(BuiltInRegistries.ITEM.get(id));
    }
}
