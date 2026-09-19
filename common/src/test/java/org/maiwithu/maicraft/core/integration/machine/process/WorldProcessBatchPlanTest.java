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
        overlappingIngredients(); earlyTriggerRejected(); finiteBatches(); competitors(); capacity(); componentIdentity();
        System.out.println("WorldProcessBatchPlanTest: shared allocation, finite supply, trigger order, competing recipes and capacity passed");
    }

    private static void overlappingIngredients() {
        // 重叠标签的有限库存分配仍有价值；只要早投原料都不匹配触发谓词，就能在保留专用材料后安全地最后投触发物。
        var inventory = empty(); inventory.set(0, new ItemStack(Items.STICK)); inventory.set(1, new ItemStack(Items.OAK_PLANKS));
        inventory.set(2, new ItemStack(Items.REDSTONE));
        var recipe = recipe("overlap", List.of(Ingredient.of(Items.REDSTONE), Ingredient.of(Items.STICK, Items.OAK_PLANKS),
                Ingredient.of(Items.STICK)), new ItemStack(Items.BRICK), 0);
        var plan = WorldProcessBatchPlan.compile(recipe, inventory, 1, List.of(recipe));
        check(plan.batch(0).get(0).is(Items.OAK_PLANKS) && plan.batch(0).get(1).is(Items.STICK) && plan.batch(0).getLast().is(Items.REDSTONE),
                "通用标签不能占走专用输入的唯一材料；触发物按原生规则最后投");
        check(inventory.get(0).getCount() == 1 && inventory.get(1).getCount() == 1, "规划不能改真实背包");
    }

    private static void earlyTriggerRejected() {
        // 这份库存虽然完全够配方使用，但木棍也匹配第0项；先入水的木棍能吞掉尚未入水的木板，不能宣称投料回执可闭环。
        var inventory = empty(); inventory.set(0, new ItemStack(Items.STICK)); inventory.set(1, new ItemStack(Items.OAK_PLANKS));
        var overlap = recipe("trigger_overlap", List.of(Ingredient.of(Items.STICK, Items.OAK_PLANKS), Ingredient.of(Items.STICK)), new ItemStack(Items.BRICK), 0);
        check(WorldProcessBatchPlan.matches(overlap.inputs(), inventory), "原料充分匹配与真实投料顺序安全是两个独立条件");
        rejects(() -> WorldProcessBatchPlan.compile(overlap, inventory, 1, List.of(overlap)), "trigger_order_ambiguous");
        check(inventory.get(0).getCount() == 1 && inventory.get(1).getCount() == 1, "预检拒绝发生在任何消费前");
        var repeated = recipe("same_item_twice", List.of(Ingredient.of(Items.BOOK), Ingredient.of(Items.BOOK)), new ItemStack(Items.PAPER), 0);
        var books = empty(); books.set(0, new ItemStack(Items.BOOK, 2));
        check(WorldProcessBatchPlan.matches(repeated.inputs(), books), "重复原料仍按真实数量分配，不能把风险误报为缺料");
        rejects(() -> WorldProcessBatchPlan.compile(repeated, books, 1, List.of(repeated)), "trigger_order_ambiguous");

        // 原料谓词可以有交集，只要本次实际分配不使用交集就可执行；后续批次若需交集材料，也必须在首批前整单拒绝。
        var alternatives = recipe("safe_selected_overlap", List.of(Ingredient.of(Items.REDSTONE, Items.STICK),
                Ingredient.of(Items.QUARTZ, Items.STICK)), new ItemStack(Items.GLASS), 0);
        var selected = empty(); selected.set(0, new ItemStack(Items.REDSTONE, 2)); selected.set(1, new ItemStack(Items.QUARTZ));
        check(WorldProcessBatchPlan.compile(alternatives, selected, 1, List.of(alternatives)).batch(0).getLast().is(Items.REDSTONE),
                "按实际已选材料判断触发风险，不笼统拒绝所有重叠谓词");
        selected.set(2, new ItemStack(Items.STICK));
        rejects(() -> WorldProcessBatchPlan.compile(alternatives, selected, 2, List.of(alternatives)), "trigger_order_ambiguous");
        check(selected.get(0).getCount() == 2 && selected.get(1).getCount() == 1 && selected.get(2).getCount() == 1,
                "不能为了安全首批而先消耗，之后才拒绝危险的第二批");
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
