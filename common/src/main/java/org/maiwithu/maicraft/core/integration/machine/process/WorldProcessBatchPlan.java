// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.maiwithu.maicraft.core.tools.ResourceAllocation;

/** 从真实背包和原生原料谓词编译有限批次；先证明所有材料够用，再按批投料，触发物总在该批最后投入。 */
public final class WorldProcessBatchPlan {
    private final List<List<ItemStack>> batches;
    private WorldProcessBatchPlan(List<List<ItemStack>> batches) { this.batches = batches; }

    public static WorldProcessBatchPlan compile(WorldProcessRecipe recipe, List<ItemStack> inventory, int count,
                                                List<? extends WorldProcessRecipe> competing) {
        if (count < 1 || count > 64 || inventory.size() > 36 || recipe.inputs().isEmpty()
                || recipe.inputs().size() > 128 || recipe.result().isEmpty()
                || recipe.triggerInputIndex() < 0 || recipe.triggerInputIndex() >= recipe.inputs().size())
            throw new IllegalArgumentException("world_process_invalid_batch_plan");
        long[] supply = inventory.stream().mapToLong(ItemStack::getCount).toArray();
        long[] demand = new long[recipe.inputs().size()]; Arrays.fill(demand, count);
        long[][] allocation = ResourceAllocation.allocate(supply, demand,
                (r, i) -> !inventory.get(r).isEmpty() && recipe.inputs().get(i).test(inventory.get(r).copyWithCount(1)));
        if (allocation == null) throw new IllegalArgumentException("world_process_insufficient_exact_inputs");
        var batches = new ArrayList<List<ItemStack>>();
        List<ItemStack> simulated = inventory.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (int batch = 0; batch < count; batch++) {
            var nativeOrder = new ArrayList<ItemStack>();
            for (int i = 0; i < demand.length; i++) {
                int selected = -1;
                for (int r = 0; r < supply.length && selected < 0; r++) if (allocation[r][i] > 0) selected = r;
                if (selected < 0) throw new IllegalStateException("world_process_allocation_incomplete");
                allocation[selected][i]--; nativeOrder.add(inventory.get(selected).copyWithCount(1));
            }
            var feed = new ArrayList<ItemStack>();
            for (int i = 0; i < nativeOrder.size(); i++) if (i != recipe.triggerInputIndex()) feed.add(nativeOrder.get(i));
            feed.add(nativeOrder.get(recipe.triggerInputIndex()));
            // 每个投料前缀都不能满足别的配方；避免最后的触发物还没到，前面的材料已被另一条规则消耗。
            for (int size = 1; size <= feed.size(); size++) for (var other : competing)
                if (!other.id().equals(recipe.id()) && matches(other.inputs(), feed.subList(0, size)))
                    throw new IllegalArgumentException("world_process_competing_recipe: " + other.id());
            for (ItemStack unit : feed) remove(simulated, unit);
            addResult(simulated, recipe.result()); batches.add(List.copyOf(feed));
        }
        return new WorldProcessBatchPlan(List.copyOf(batches));
    }

    /** 只做原料匹配，不调用原生assemble，不制造样品；重复标签依旧经过同一个有限库存分配器。 */
    static boolean matches(List<Ingredient> ingredients, List<ItemStack> inventory) {
        if (ingredients.isEmpty() || ingredients.size() > inventory.stream().mapToInt(ItemStack::getCount).sum()) return false;
        long[] supply = inventory.stream().mapToLong(ItemStack::getCount).toArray();
        long[] demand = new long[ingredients.size()]; Arrays.fill(demand, 1);
        return ResourceAllocation.allocate(supply, demand, (r, i) -> ingredients.get(i).test(inventory.get(r))) != null;
    }

    public int size() { return batches.size(); }
    public List<ItemStack> batch(int index) { return batches.get(index).stream().map(ItemStack::copy).toList(); }

    static void requireSpace(List<ItemStack> inventory, List<ItemStack> inputs, ItemStack result) {
        // 暂停期间别的物品可能占走空位；每批消费前再次检查该批原料离包后的实际成品容量。
        var simulated = inventory.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (ItemStack input : inputs) for (int n = 0; n < input.getCount(); n++) remove(simulated, input);
        addResult(simulated, result);
    }

    private static void remove(List<ItemStack> inventory, ItemStack unit) {
        for (ItemStack stack : inventory) if (ItemStack.isSameItemSameComponents(stack, unit) && !stack.isEmpty()) {
            stack.shrink(1); return;
        }
        throw new IllegalStateException("world_process_simulated_input_missing");
    }

    private static void addResult(List<ItemStack> inventory, ItemStack result) {
        int remaining = result.getCount();
        // 模拟每一批“材料离包 -> 成品回包”的空间变化，不能只看全部加工结束后理论上能空出多少格。
        for (ItemStack stack : inventory) if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, result)) {
            int add = Math.min(remaining, Math.max(0, stack.getMaxStackSize() - stack.getCount())); stack.grow(add); remaining -= add;
        }
        for (int i = 0; remaining > 0 && i < inventory.size(); i++) if (inventory.get(i).isEmpty()) {
            int add = Math.min(remaining, result.getMaxStackSize()); inventory.set(i, result.copyWithCount(add)); remaining -= add;
        }
        if (remaining > 0) throw new IllegalArgumentException("world_process_output_inventory_space_required");
    }
}
