// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.FluidState;

/** 验证通用配方和有限背包的分配，不加载AE2、不制造世界物品；不同输入数量、标签和成品都走同一个编译器。 */
public final class WorldProcessBatchPlanTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        overlappingIngredients(); finiteBatches(); competitors(); capacity(); componentIdentity();
        System.out.println("WorldProcessBatchPlanTest: shared allocation, finite supply, trigger order, competing recipes and capacity passed");
    }

    private static void overlappingIngredients() {
        var inventory = empty(); inventory.set(0, new ItemStack(Items.STICK)); inventory.set(1, new ItemStack(Items.OAK_PLANKS));
        var recipe = recipe("overlap", List.of(Ingredient.of(Items.STICK, Items.OAK_PLANKS), Ingredient.of(Items.STICK)), new ItemStack(Items.BRICK), 0);
        var plan = WorldProcessBatchPlan.compile(recipe, inventory, 1, List.of(recipe));
        check(plan.batch(0).get(0).is(Items.STICK) && plan.batch(0).get(1).is(Items.OAK_PLANKS),
                "通用标签不能占走专用输入的唯一材料；触发物按原生规则最后投");
        check(inventory.get(0).getCount() == 1 && inventory.get(1).getCount() == 1, "规划不能改真实背包");
    }

    private static void finiteBatches() {
        var inventory = empty(); inventory.set(0, new ItemStack(Items.REDSTONE, 2)); inventory.set(1, new ItemStack(Items.QUARTZ, 2));
        var recipe = recipe("two_inputs", List.of(Ingredient.of(Items.REDSTONE), Ingredient.of(Items.QUARTZ)), new ItemStack(Items.GLASS, 2), 0);
        var plan = WorldProcessBatchPlan.compile(recipe, inventory, 2, List.of(recipe));
        check(plan.size() == 2 && plan.batch(0).size() == 2 && plan.batch(1).getLast().is(Items.REDSTONE), "批数与每批原料由配方和请求决定");
        plan.batch(0).get(0).setCount(40);
        check(plan.batch(0).get(0).getCount() == 1, "对外返回的材料副本不能扩大后续投料");
        rejects(() -> WorldProcessBatchPlan.compile(recipe, inventory, 3, List.of(recipe)), "insufficient");
        check(inventory.get(0).getCount() == 2, "少料时不能偷偷降批或消费部分材料");
    }

    private static void competitors() {
        var inventory = empty(); inventory.set(0, new ItemStack(Items.BOOK)); inventory.set(1, new ItemStack(Items.REDSTONE));
        var desired = recipe("desired", List.of(Ingredient.of(Items.REDSTONE), Ingredient.of(Items.BOOK)), new ItemStack(Items.PAPER), 0);
        var competing = recipe("other", List.of(Ingredient.of(Items.BOOK)), new ItemStack(Items.STICK), 0);
        rejects(() -> WorldProcessBatchPlan.compile(desired, inventory, 1, List.of(desired, competing)), "competing_recipe");
        var duplicate = recipe("duplicate", List.of(Ingredient.of(Items.BOOK), Ingredient.of(Items.BOOK)), new ItemStack(Items.PAPER), 0);
        rejects(() -> WorldProcessBatchPlan.compile(duplicate, inventory, 1, List.of(duplicate)), "insufficient");
    }

    private static void capacity() {
        var inventory = empty(); for (int i = 0; i < 36; i++) inventory.set(i, new ItemStack(Items.STONE, 64));
        inventory.set(0, new ItemStack(Items.REDSTONE, 64));
        var recipe = recipe("capacity", List.of(Ingredient.of(Items.REDSTONE)), new ItemStack(Items.BOOK), 0);
        rejects(() -> WorldProcessBatchPlan.compile(recipe, inventory, 1, List.of(recipe)), "space");
        inventory.set(0, new ItemStack(Items.REDSTONE));
        check(WorldProcessBatchPlan.compile(recipe, inventory, 1, List.of(recipe)).size() == 1, "当批消耗恰好腾出一格时可以收成品");
    }

    private static void componentIdentity() {
        ItemStack named = new ItemStack(Items.BOOK); named.set(DataComponents.CUSTOM_NAME, Component.literal("有名原料"));
        check(!WorldProcessDropReceipt.sameItems(List.of(named), List.of(new ItemStack(Items.BOOK))), "同名注册物品不能掩盖组件变化");
        check(WorldProcessDropReceipt.sameItems(List.of(named, named), List.of(named.copyWithCount(2))), "同组件合堆仍按真实数量守恒");
        check(!WorldProcessDropReceipt.sameItems(List.of(named), List.of(named.copyWithCount(2))), "不能把外来同类物品一并认领");
    }

    private static List<ItemStack> empty() { var values = new ArrayList<ItemStack>(); for (int i = 0; i < 36; i++) values.add(ItemStack.EMPTY); return values; }
    private static WorldProcessRecipe recipe(String id, List<Ingredient> inputs, ItemStack output, int trigger) {
        // 测试只提供规则，不提供世界写入入口；同一生产执行器不能依赖某个测试物品名走捷径。
        return new WorldProcessRecipe() {
            public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath("test", id); }
            public List<Ingredient> inputs() { return List.copyOf(inputs); }
            public ItemStack result() { return output.copy(); }
            public boolean supports(FluidState state) { return state != null && !state.isEmpty(); }
            public boolean isFluid() { return true; }
            public JsonObject describe() { return new JsonObject(); }
            public int triggerInputIndex() { return trigger; }
        };
    }
    private static void rejects(Runnable action, String code) {
        try { action.run(); } catch (IllegalArgumentException rejected) { check(rejected.getMessage().contains(code), rejected.getMessage()); return; }
        throw new AssertionError("expected " + code);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
