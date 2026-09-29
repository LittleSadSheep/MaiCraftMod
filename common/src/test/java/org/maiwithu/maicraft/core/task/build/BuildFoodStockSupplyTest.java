// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import org.maiwithu.maicraft.core.inventory.FoodMaterialBudget;
import org.maiwithu.maicraft.core.inventory.OrdinaryFood;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** 只注入原生供料的观察结果；验证自动选食和预留账，不将夹具库存变化声称为实机取物成功。 */
public final class BuildFoodStockSupplyTest {
    private static final ResourceLocation KELP = ResourceLocation.parse("minecraft:dried_kelp");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        propertiesAndReservations(); declaredRecipeInputsStayReserved(); stockObservationAndReceipt(); noStockAndUncertainty(); preparationUsesStock(); pauseRetainsTransaction();
        System.out.println("BuildFoodStockSupplyTest: passed");
    }
    private static void propertiesAndReservations() {
        check(OrdinaryFood.ordinary(new ItemStack(Items.DRIED_KELP)), "dried kelp is ordinary food by its native food component");
        // 使用已注册但不在旧候选名单里的食物，不在冻结后的游戏注册表中临时捏造模组物品。
        for (Item food : List.of(Items.SWEET_BERRIES, Items.GLOW_BERRIES, Items.RABBIT_STEW, Items.COOKED_COD))
            check(OrdinaryFood.ordinary(new ItemStack(food)), "food outside the old list uses the same property rule");
        var reserved = new FoodMaterialBudget(Map.of(Items.DRIED_KELP, 8L), Set.of(), true);
        check(OrdinaryFood.choose(List.of(new ItemStack(Items.DRIED_KELP, 8)), 6, reserved) == null,
                "the exact quantity reserved for later work is never food");
        check(OrdinaryFood.choose(List.of(new ItemStack(Items.DRIED_KELP, 9)), 6, reserved) == Items.DRIED_KELP,
                "only the carried surplus above the work floor can be eaten");
        check(OrdinaryFood.stock(Map.of(KELP, 8L), List.of(), 6, reserved) == null,
                "network stock that only covers work is not available food");
        var stock = OrdinaryFood.stock(Map.of(KELP, 10L), List.of(), 6, reserved);
        check(stock != null && stock.count() == 10, "stock transfer first covers the reserved eight, leaving only two edible surplus items");
        // 以配方依赖代替固定海带黑名单；连接件的原料数量尚未算清时整类保留，有另一种口粮就吃另一种。
        var future = FoodMaterialBudget.plan(Map.of(), Set.of(Items.STICK), item -> item == Items.STICK ? Set.of(Items.DRIED_KELP) : Set.of());
        check(future.spare(Items.DRIED_KELP, 1000) == 0
                && OrdinaryFood.choose(List.of(new ItemStack(Items.DRIED_KELP, 64), new ItemStack(Items.CARROT)), 6, future) == Items.CARROT,
                "an unresolved later recipe protects its food ingredient without blocking unrelated food");
        var cycle = FoodMaterialBudget.plan(Map.of(Items.STICK, 1L), Set.of(), item -> item == Items.STICK ? Set.of(Items.DRIED_KELP) : Set.of(Items.STICK));
        check(cycle.complete() && cycle.spare(Items.DRIED_KELP, 10) == 0, "recipe cycles terminate without releasing protected food");
        check(OrdinaryFood.choose(List.of(new ItemStack(Items.DRIED_KELP)), 6,
                new FoodMaterialBudget(Map.of(), Set.of(), false)) == null, "unknown work budget is not zero demand");
    }
    private static void stockObservationAndReceipt() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(6);
            var stocks = new AtomicReference<Optional<StockEvidence.Snapshot>>(Optional.empty());
            var records = new ArrayList<Ae2SupplyTaskRecord>(); var supply = supply(stocks, records, false);
            var owner = owner();
            check(supply.canAttempt(h.player, owner, FoodMaterialBudget.EMPTY), "unknown network stock permits one observation");
            supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null);
            check(records.size() == 1 && records.getFirst().request.operation() == Ae2ResourceSupply.Operation.OBSERVE,
                    "no fixed food list is sent to the stock observation");
            supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> { stocks.set(Optional.of(snapshot(40))); return TaskState.SUCCESS; });
            supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null);
            var request = records.getLast().request;
            check(records.size() == 2 && request.groups().getFirst().itemId().equals(KELP)
                    && !request.allowCrafting() && request.wirelessOnly(), "actual dried-kelp stock is chosen without crafting or chest travel");
            // 提前注入预测到货，但事务仍在等待；备餐不能越过回执直接把它当成可吃物品。
            h.inventory.setItem(0, new ItemStack(Items.DRIED_KELP, 20));
            check(supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null) == BuildFoodStockSupply.Status.RUNNING,
                    "predicted stock cannot bypass the pending native transfer");
            check(supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> TaskState.SUCCESS) == BuildFoodStockSupply.Status.READY,
                    "confirmed transfer and actual ordinary carried food make the supply ready");
        }
    }
    private static void declaredRecipeInputsStayReserved() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 给原版配方管理器装入一份明确测试配方，验证生产入口会读取后续工序，而非只测独立算术函数。
            var recipe = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DRIED_KELP)));
            h.player.connection.getRecipeManager().replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:reserved_food"), recipe)));
            var whole = owner(); whole.futureWorkItems(Set.of(Items.STICK)); var batch = owner(); whole.copyExecutionContextTo(batch);
            var budget = BuildFoodMaterials.inspect(h.player, batch);
            check(batch.futureWorkItems().contains(Items.STICK) && budget.complete() && budget.spare(Items.DRIED_KELP, 64) == 0,
                    "the actual recipe manager and copied batch retain a later food ingredient");
            // 当前任务正在合成，而施工单仅负责摆工作台时，也必须沿当前目标配方保留干海带。
            var goal = Goal.fromJson(JsonParser.parseString("{ability:'maicraft:craft',outcome:'make current output',parameters:{item_id:'minecraft:stick',count:1}}").getAsJsonObject());
            var active = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            var currentBudget = BuildFoodMaterials.inspect(h.player, owner(), active);
            check(currentBudget.complete() && currentBudget.spare(Items.DRIED_KELP, 6) == 0
                    && OrdinaryFood.choose(List.of(new ItemStack(Items.DRIED_KELP, 6)), 17, currentBudget) == null,
                    "temporary crafting-surface preparation cannot eat the six ingredients of the current recipe");
            h.player.getFoodData().setFoodLevel(6); h.inventory.setItem(0, new ItemStack(Items.DRIED_KELP, 64));
            h.inventory.setItem(1, new ItemStack(Items.CARROT, 2));
            var meals = new ArrayList<Item>(); var prep = new BuildFoodPreparation((p, r) -> { meals.add(r.item); return new ResultTask(false); });
            prep.tick(h.player, batch, task -> null);
            check(meals.equals(List.of(Items.CARROT)) && h.inventory.getItem(0).getCount() == 64,
                    "real build preparation chooses unrelated food while preserving the future recipe's kelp");
            prep.stop(h.player);
        }
    }
    private static void noStockAndUncertainty() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(6);
            var stocks = new AtomicReference<Optional<StockEvidence.Snapshot>>(Optional.empty());
            var records = new ArrayList<Ae2SupplyTaskRecord>(); var supply = supply(stocks, records, false); var owner = owner();
            supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null);
            supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> { stocks.set(Optional.of(snapshot(0))); return TaskState.SUCCESS; });
            check(supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null) == BuildFoodStockSupply.Status.UNAVAILABLE,
                    "a complete empty stock observation permits another source");
            for (int i = 0; i < 10; i++) {
                check(!supply.canAttempt(h.player, owner, FoodMaterialBudget.EMPTY), "empty stock cannot reopen the terminal every tick"); h.nextTick();
            }
            check(records.size() == 1, "no repeated AE openings after a settled empty observation");
            var uncertain = supply(stocks, records, true); stocks.set(Optional.of(snapshot(40)));
            uncertain.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null);
            check(uncertain.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> TaskState.FAILED) == BuildFoodStockSupply.Status.UNCERTAIN
                    && !uncertain.canAttempt(h.player, owner, FoodMaterialBudget.EMPTY), "uncertain transfer cannot be automatically resent");
            var restricted = new BuildTaskRecord("inventory-only", 1000, List.of(), false, true);
            check(!supply(stocks, records, false).canAttempt(h.player, restricted, FoodMaterialBudget.EMPTY), "inventory-only policy cannot open AE for food");
            var modified = new ItemStack(Items.DRIED_KELP); modified.set(DataComponents.FOOD, new ItemStack(Items.ROTTEN_FLESH).get(DataComponents.FOOD));
            h.inventory.setItem(0, modified);
            check(OrdinaryFood.stock(Map.of(KELP, 40L), h.inventory.items, 6, FoodMaterialBudget.EMPTY) == null,
                    "an unsafe carried component variant prevents ID-only food selection");
        }
    }
    private static void preparationUsesStock() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(6);
            var stocks = new AtomicReference<>(Optional.of(snapshot(40))); var records = new ArrayList<Ae2SupplyTaskRecord>();
            var supply = supply(stocks, records, false); var meals = new ArrayList<Item>();
            var prep = new BuildFoodPreparation((p, r) -> { meals.add(r.item); return new ResultTask(false); }, supply); var owner = owner();
            check(prep.shouldPrepare(h.player, owner), "healthy hunger six can prepare real stock before continuing work");
            for (int tick = 0; tick < 7 && meals.isEmpty(); tick++) {
                prep.tick(h.player, owner, task -> { h.inventory.setItem(0, new ItemStack(Items.DRIED_KELP, 20)); return TaskState.SUCCESS; });
                h.nextTick();
            }
            check(meals.equals(List.of(Items.DRIED_KELP)) && records.size() == 1, "build preparation reaches the native eater after stock settlement");
            prep.stop(h.player);
        }
    }
    private static BuildTaskRecord owner() {
        var owner = new BuildTaskRecord("food-stock", 2000, List.of(), false, true);
        owner.toolSupply(new BuildTaskRecord.ToolSupply(MaterialPolicy.ORDINARY, List.of(), false, List.of())); return owner;
    }
    private static void pauseRetainsTransaction() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(6);
            var stocks = new AtomicReference<>(Optional.of(snapshot(40))); var records = new ArrayList<Ae2SupplyTaskRecord>();
            var supply = supply(stocks, records, false); var owner = owner();
            supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null); supply.pause(h.player);
            for (int i = 0; i < 700; i++) h.nextTick();
            check(supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> null) == BuildFoodStockSupply.Status.RUNNING
                    && records.size() == 1, "defense pause neither times out nor resubmits the same pending stock transaction");
            h.inventory.setItem(0, new ItemStack(Items.DRIED_KELP, 20));
            check(supply.tick(h.player, owner, FoodMaterialBudget.EMPTY, task -> TaskState.SUCCESS) == BuildFoodStockSupply.Status.READY,
                    "the original stock receipt can settle after defense returns the body");
        }
    }
    private static StockEvidence.Snapshot snapshot(long kelp) { return new StockEvidence.Snapshot(StockEvidence.Source.AE2, Map.of(KELP, kelp), Set.of(), 0); }
    private static BuildFoodStockSupply supply(AtomicReference<Optional<StockEvidence.Snapshot>> stocks, List<Ae2SupplyTaskRecord> records, boolean uncertain) {
        return new BuildFoodStockSupply(p -> true, p -> stocks.get(), (p, r) -> { records.add(r); return new ResultTask(uncertain); });
    }
    private record ResultTask(boolean uncertain) implements Task {
        public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
        public void stop(LocalPlayer player, StopReason reason) { /* 夹具没有持用或界面，停止只交回模拟控制。 */ }
        public TaskResult result(TaskState state) { return uncertain ? TaskResult.fail("fixture transfer uncertain", Map.of("outcome_uncertain", true)) : TaskResult.ok("fixture settled"); }
        public String name() { return "observed stock fixture"; }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
