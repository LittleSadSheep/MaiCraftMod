package org.maiwithu.maicraft.core.task.craft;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.core.PlayerInv;

/** 为一批合成选择真实背包材料及目标格；标签和模组组件条件始终由原生材料谓词决定。 */
public final class CraftingPlacementPlan {
    public record Entry(int sourceSlot, int targetSlot, Ingredient ingredient, ItemStack sample) {}
    private record Demand(Ingredient ingredient, int targetSlot) {}
    private CraftingPlacementPlan() {}

    /** 返回空列表表示当前背包无法摆出完整一批；这里只读槽位，尚不提交任何游戏动作。 */
    public static List<Entry> create(LocalPlayer player, CraftingRecipe recipe, CraftingContainer grid) {
        // 有序配方按原宽度跨到实际网格下一行，无序配方连续放置；不能把 1×2 火把误摆成横向两格。
        if (!recipe.canCraftInDimensions(grid.getWidth(), grid.getHeight()))
            throw new IllegalArgumentException("recipe does not fit the active crafting grid");
        var menu = player.containerMenu;
        var demands = new ArrayList<Demand>();
        var ingredients = recipe.getIngredients();
        for (int index = 0; index < ingredients.size(); index++) {
            Ingredient ingredient = ingredients.get(index);
            if (ingredient.isEmpty()) continue;
            int targetIndex = recipe instanceof ShapedRecipe shaped
                    ? index / shaped.getWidth() * grid.getWidth() + index % shaped.getWidth() : demands.size();
            int target = -1;
            for (int slot = 0; slot < menu.slots.size(); slot++) {
                Slot candidate = menu.getSlot(slot);
                if (candidate.container == grid && candidate.getContainerSlot() == targetIndex) { target = slot; break; }
            }
            if (target < 0) throw new IllegalArgumentException("crafting grid slot is not exposed by the active menu");
            demands.add(new Demand(ingredient, target));
        }
        int size = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        int[] sources = new int[size], remaining = new int[size], assigned = new int[demands.size()];
        Arrays.fill(sources, -1); Arrays.fill(assigned, -1);
        // 背包、工作台及模组合成菜单的槽号可能不同，只依据容器身份与实际背包索引寻找材料槽。
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            Slot candidate = menu.getSlot(slot); int index = candidate.getContainerSlot();
            if (candidate.container == player.getInventory() && index >= 0 && index < size && candidate.mayPickup(player)) {
                sources[index] = slot; remaining[index] = candidate.getItem().getCount();
            }
        }
        boolean[][] accepts = new boolean[demands.size()][size];
        for (int demand = 0; demand < demands.size(); demand++) {
            for (int source = 0; source < size; source++) {
                ItemStack stack = player.getInventory().getItem(source);
                accepts[demand][source] = sources[source] >= 0 && !stack.isEmpty()
                        && demands.get(demand).ingredient().test(stack)
                        && menu.getSlot(demands.get(demand).targetSlot()).mayPlace(stack);
            }
            // 宽泛木板标签可能先拿走精确橡木输入；沿增广路径重新分配，避免明明齐料却被贪心选料卡住。
            if (!assign(demand, accepts, remaining, assigned, new boolean[size])) return List.of();
        }
        var result = new ArrayList<Entry>();
        for (int index = 0; index < demands.size(); index++) {
            var demand = demands.get(index); int source = assigned[index];
            result.add(new Entry(sources[source], demand.targetSlot(), demand.ingredient(),
                    player.getInventory().getItem(source).copyWithCount(1)));
        }
        return List.copyOf(result);
    }

    private static boolean assign(int demand, boolean[][] accepts, int[] remaining, int[] assigned, boolean[] visited) {
        for (int source = 0; source < remaining.length; source++) {
            if (!accepts[demand][source] || visited[source]) continue;
            visited[source] = true;
            if (remaining[source] > 0) { remaining[source]--; assigned[demand] = source; return true; }
            for (int previous = 0; previous < assigned.length; previous++) {
                if (assigned[previous] == source && assign(previous, accepts, remaining, assigned, visited)) {
                    assigned[demand] = source; return true;
                }
            }
        }
        return false;
    }

    /** 配方簿仅适用于已解锁且能够从普通材料堆中摆出的配方；其余合法输入用真实槽位点击处理。 */
    public static boolean recipeBookUsable(LocalPlayer player, RecipeHolder<?> holder) {
        if (player.getRecipeBook() == null || !player.getRecipeBook().contains(holder)) return false;
        var contents = new StackedContents(); player.getInventory().fillStackedContents(contents);
        var ids = new IntArrayList();
        if (!contents.canCraft(holder.value(), ids)) return false;
        var ingredients = holder.value().getIngredients();
        if (ids.size() != ingredients.size()) return false;
        for (int index = 0; index < ingredients.size(); index++) {
            if (ingredients.get(index).isEmpty()) continue;
            ItemStack sample = StackedContents.fromStackingIndex(ids.getInt(index));
            // 配方簿按物品编号选料，无法表达组件条件；再用完整谓词和可搬动的实际材料堆核对。
            if (sample.isEmpty() || !ingredients.get(index).test(sample)
                    || player.getInventory().findSlotMatchingUnusedItem(sample) < 0) return false;
        }
        return true;
    }
}
