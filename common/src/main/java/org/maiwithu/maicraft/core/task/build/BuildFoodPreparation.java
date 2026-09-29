// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.inventory.FoodMaterialBudget;
import org.maiwithu.maicraft.core.inventory.OrdinaryFood;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.inventory.EatCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EatItemTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Locale;

/** 施工安全间隙先吃随身普通食物，再有限等待低血量恢复；只观察身体和物品，实际持用交给原生进食任务。 */
final class BuildFoodPreparation {
    enum Status { READY, RUNNING, FAILED }
    static final long MEAL_TIMEOUT = 200, RECOVERY_IDLE_TIMEOUT = 200, RECOVERY_TIMEOUT = 900, EPISODE_TIMEOUT = 1800;
    private final BiFunction<LocalPlayer, EatItemTaskRecord, Task> eater;
    private final BuildFoodStockSupply stockSupply;
    private final BuildFoodFarmSupply farmSupply = new BuildFoodFarmSupply();
    private FoodMaterialBudget budget = FoodMaterialBudget.EMPTY;
    private BuildTaskRecord budgetOwner;
    private boolean procuring, farming;
    private final List<Map<String, Object>> receipts = new ArrayList<>();
    private Task child;
    private EatItemTaskRecord meal;
    private boolean maintaining, recovery, uncertain;
    private long deadline, restStarted = -1, healthProgress, pausedAt = -1;
    private long deferredUntil;
    private String preparationObservation;
    private int serial, episodeMeals, confirmedItems, confirmedMeals, countBefore, foodBefore;
    private float healthBefore, lastHealth;
    private String failure, message;
    private FailureType failureType = FailureType.UNKNOWN;

    BuildFoodPreparation() { this(EatCompanionTask::new); }
    BuildFoodPreparation(BiFunction<LocalPlayer, EatItemTaskRecord, Task> eater) { this(eater, new BuildFoodStockSupply()); }
    BuildFoodPreparation(BiFunction<LocalPlayer, EatItemTaskRecord, Task> eater, BuildFoodStockSupply stockSupply) {
        this.eater = eater; this.stockSupply = stockSupply;
    }

    static boolean needs(boolean creative, int food, float health, float maxHealth) {
        // 受伤但饱食度十七时也不能自然回血，不能等到快饿或只剩六点生命才吃；健康身体仍沿用普通补食阈值。
        return !creative && (food <= 14 || health < Math.min(8, maxHealth) || food < 18 && health < maxHealth);
    }
    /** 危急身体状态优先尝试补食；这个阈值决定备餐优先级，不把吃饱或回血达标作为施工准入条件。 */
    private static boolean urgent(int food, float health, float maxHealth) { return food <= 0 || health < Math.min(8, maxHealth); }
    boolean active() { return maintaining || child != null || procuring || farming; }
    boolean shouldPrepare(LocalPlayer player) {
        if (failure != null || active()) return true;
        if (player.level().getGameTime() < deferredUntil) return false;
        if (player.getAbilities().instabuild) return false;
        int food = player.getFoodData().getFoodLevel(); float health = player.getHealth(), max = player.getMaxHealth();
        // 缺少普通食物但仍能安全工作时，不反复停导航，也不拦住用于恢复供给的工作台准备。
        return needs(false, food, health, max) && (urgent(food, health, max) || carried(player, food) != null);
    }
    /** 只有真实授权的现货入口能触发额外备餐；检查前先保留整份任务的食物材料。 */
    boolean shouldPrepare(LocalPlayer player, BuildTaskRecord owner) {
        prepareBudget(player, owner);
        if (!active() && player.level().getGameTime() < deferredUntil) return false;
        return shouldPrepare(player) || needs(player.getAbilities().instabuild, player.getFoodData().getFoodLevel(),
                player.getHealth(), player.getMaxHealth()) && (stockSupply.canAttempt(player, owner, budget)
                        || farmSupply.canAttempt(player, owner, budget));
    }
    private void prepareBudget(LocalPlayer player, BuildTaskRecord owner) {
        if (budgetOwner != owner) { budget = BuildFoodMaterials.inspect(player, owner); budgetOwner = owner; }
    }
    private Item carried(LocalPlayer player, int food) { return OrdinaryFood.choose(player.getInventory().items, food, budget); }
    String failure() { return failure; }
    String message() { return message; }
    FailureType failureType() { return failureType; }
    long deadline() { return deadline; }
    List<Map<String, Object>> receipts() { return List.copyOf(receipts); }

    Status tick(LocalPlayer player, BuildTaskRecord owner, Function<Task, TaskState> runner) {
        if (failure != null) return Status.FAILED;
        if (player.getAbilities().instabuild) { stop(player); return Status.READY; }
        prepareBudget(player, owner);
        long now = player.level().getGameTime();
        // 补食只是施工间隙的身体维护；失败后留出恢复间隔，不把同一吃饭尝试变成每刻重试的开工门槛。
        if (!active() && now < deferredUntil && player.getHealth() > 0) return Status.READY;
        if (pausedAt >= 0) {
            long elapsed = now - pausedAt; deadline += elapsed; healthProgress += elapsed;
            if (restStarted >= 0) restStarted += elapsed; pausedAt = -1;
        }
        int food = player.getFoodData().getFoodLevel(); float health = player.getHealth();
        if (health <= 0) return fail("build_body_not_alive", "Construction stopped because the body is no longer alive", FailureType.UNKNOWN);
        if (!maintaining) {
            if (!needs(false, food, health, player.getMaxHealth())) return Status.READY;
            if (!urgent(food, health, player.getMaxHealth()) && carried(player, food) == null
                    && !stockSupply.canAttempt(player, owner, budget) && !farmSupply.canAttempt(player, owner, budget)) return Status.READY;
            // 长任务先松开施工动作，再吃到接近饱；危急低血量多补到二十，给真实自然恢复留出条件。
            maintaining = true; recovery = health < Math.min(8, player.getMaxHealth()); episodeMeals = 0;
            deadline = now + EPISODE_TIMEOUT; restStarted = -1; lastHealth = health; healthProgress = now;
        }
        recovery |= health < Math.min(8, player.getMaxHealth());
        if (now >= deadline) return defer(player, "build_food_timeout", "Food preparation exceeded its bounded time");
        if (procuring) {
            // 先结清查询或取物，再读取背包；不能越过菜单确认直接吃客户端预测出来的物品。
            var status = stockSupply.tick(player, owner, budget, runner);
            if (status == BuildFoodStockSupply.Status.RUNNING) return Status.RUNNING;
            procuring = false;
            if (status == BuildFoodStockSupply.Status.UNCERTAIN) {
                uncertain = true;
                return defer(player, "build_food_stock_uncertain", "Food stock transfer did not settle; its receipt remains available");
            }
            return Status.RUNNING;
        }
        if (farming) {
            // 采收、补种和返工位均完成后才吃，保留种苗也保留原施工锚点。
            var result = farmSupply.tick(player, owner, budget, runner);
            if (result.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) return Status.RUNNING;
            farming = false;
            if (Boolean.TRUE.equals(result.receipt().get("outcome_uncertain"))
                    || "crop_replant_incomplete".equals(result.receipt().get("failure_code"))) {
                uncertain = true;
                return defer(player, "build_food_harvest_uncertain", "Crop food preparation did not settle; its receipt remains available");
            }
            return Status.RUNNING;
        }
        if (child != null) {
            TaskState terminal;
            if (now >= meal.getDeadlineGameTime()) { child.stop(player, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT; }
            else terminal = runner.apply(child);
            if (terminal == null) return Status.RUNNING;
            boolean consumed = finishMeal(player, terminal);
            if (!consumed) return defer(player, "build_food_unconfirmed", "The native food action did not confirm consumption");
            return Status.RUNNING; // 吃完和继续施工分开一刻，先让持用和必要的背包界面完整收尾。
        }
        // 受伤时本次补到二十，给真实自然恢复留余量；这里只吃已有食物，不直接写生命值，也不要求普通伤势等到满血。
        int targetFood = recovery || health < player.getMaxHealth() ? 20 : 18;
        if (food < targetFood) {
            Item chosen = carried(player, food);
            if (chosen == null) {
                if (stockSupply.canAttempt(player, owner, budget)) { procuring = true; return Status.RUNNING; }
                if (farmSupply.canAttempt(player, owner, budget)) { farming = true; return Status.RUNNING; }
                // 补食中途耗尽也按当前身体判断；已经越过低生命/低饥饿底线，就将角色交回原工作。
                if (!urgent(food, health, player.getMaxHealth())) { maintaining = false; recovery = false; restStarted = -1; return Status.READY; }
                return defer(player, "build_food_unavailable", "No ordinary food was available for this preparation attempt");
            }
            if (++episodeMeals > 32) return defer(player, "build_food_action_limit", "Food preparation exhausted its bounded meal count");
            countBefore = PlayerInv.count(player.getInventory(), chosen); foodBefore = food; healthBefore = health;
            String id = BuiltInRegistries.ITEM.getKey(chosen).toString();
            meal = new EatItemTaskRecord(owner.getToolCallId() + "/build-food-" + (++serial), now + MEAL_TIMEOUT, chosen, id);
            child = eater.apply(player, meal);
            return Status.RUNNING;
        }
        if (recovery && health < Math.min(8, player.getMaxHealth())) {
            // 已吃够后有限等待真实恢复；受伤或迟迟没恢复就结束本次维护，避免吃饭流程长期占住施工任务。
            if (restStarted < 0) { restStarted = now; healthProgress = now; lastHealth = health; }
            if (health < lastHealth) return defer(player, "build_health_declining", "Health declined while resting after food");
            if (health > lastHealth) { lastHealth = health; healthProgress = now; }
            if (now - healthProgress >= RECOVERY_IDLE_TIMEOUT || now - restStarted >= RECOVERY_TIMEOUT)
                return defer(player, "build_health_recovery_unconfirmed", "Natural recovery did not reach the preparation target within its wait");
            return Status.RUNNING;
        }
        maintaining = false; recovery = false; return Status.READY;
    }

    // 无任务材料约束的纯筛选入口仍供观察与回归使用；生产补食始终调用携带预算的 carried。
    static Item choose(List<ItemStack> inventory, int food) {
        return OrdinaryFood.choose(inventory, food, FoodMaterialBudget.EMPTY);
    }

    private boolean finishMeal(LocalPlayer player, TaskState terminal) {
        TaskResult result = child.result(terminal);
        int after = PlayerInv.count(player.getInventory(), meal.item), delta = countBefore - after;
        int foodAfter = player.getFoodData().getFoodLevel();
        boolean verified = terminal == TaskState.SUCCESS && result != null && result.success()
                && delta == 1 && foodAfter > foodBefore && !player.isUsingItem();
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("item_id", BuiltInRegistries.ITEM.getKey(meal.item).toString());
        receipt.put("terminal_state", terminal.name().toLowerCase(Locale.ROOT));
        receipt.put("confirmed", verified); receipt.put("observed_count_before", countBefore); receipt.put("observed_count_after", after);
        receipt.put("observed_consumed", Math.max(0, delta)); receipt.put("food_before", foodBefore); receipt.put("food_after", foodAfter);
        receipt.put("health_before", healthBefore); receipt.put("health_after", player.getHealth());
        if (result != null && result.message() != null && terminal != TaskState.CANCELLED) receipt.put("native_message", result.message());
        if (receipts.size() == 32) receipts.removeFirst(); receipts.add(Map.copyOf(receipt));
        if (verified) { confirmedItems++; confirmedMeals++; } else uncertain |= delta != 0 || terminal == TaskState.TIMEOUT || terminal == TaskState.CANCELLED;
        child = null; meal = null; return verified;
    }

    void stop(LocalPlayer player) {
        // 暂停或取消时明确结束自己的持用和选物界面；保留已经观察到的数量变化，不声称食物一定没吃掉。
        interruptMeal(player, TaskState.CANCELLED); stockSupply.stop(player); farmSupply.stop(player); procuring = false; farming = false;
        maintaining = false; recovery = false; restStarted = -1;
    }
    /** 查询库存或采田时被防卫抢占，保留原子任务；普通持用进食仍沿既有中断对账处理。 */
    void pause(LocalPlayer player) {
        if (!procuring && !farming) { stop(player); return; }
        if (procuring) stockSupply.pause(player);
        if (farming) farmSupply.pause(player);
        if (pausedAt < 0) pausedAt = player.level().getGameTime();
    }
    private void interruptMeal(LocalPlayer player, TaskState terminal) {
        if (child != null) { child.stop(player, Task.StopReason.REPLACED); finishMeal(player, terminal); }
    }
    private Status fail(String code, String detail, FailureType type) {
        failure = code; message = detail; failureType = type; maintaining = false; return Status.FAILED;
    }
    private Status defer(LocalPlayer player, String code, String detail) {
        // 先结束自己持有的进食、备餐和界面操作，再交回原施工；如实保留身体维护结果，不据此判建造失败。
        stop(player); preparationObservation = code + ": " + detail;
        deferredUntil = player.level().getGameTime() + 400;
        return Status.READY;
    }
    Map<String, Object> progress(LocalPlayer player) {
        var data = new LinkedHashMap<String, Object>();
        data.put("phase", failure != null ? "failed" : child != null ? "eating" : maintaining ? "recovering_or_refilling" : "ready");
        data.put("confirmed_food_items", confirmedItems); data.put("confirmed_food_actions", confirmedMeals);
        data.put("food", player.getFoodData().getFoodLevel()); data.put("health", player.getHealth()); data.put("outcome_uncertain", uncertain);
        data.put("stock_supply", stockSupply.progress()); data.put("material_budget_complete", budget.complete());
        data.put("farm_supply", farmSupply.progress());
        if (preparationObservation != null) data.put("preparation_observation", preparationObservation);
        data.put("preparation_deferred", player.level().getGameTime() < deferredUntil);
        data.put("held_food_materials", budget.held().stream().filter(item -> OrdinaryFood.ordinary(new ItemStack(item)))
                .map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).sorted().toList());
        // 回执区分缺少补食与紧急停工，不将继续施工误报为已吃饱或已恢复生命。
        data.put("refill_deferred", !player.getAbilities().instabuild && !active()
                && needs(false, player.getFoodData().getFoodLevel(), player.getHealth(), player.getMaxHealth())
                && !urgent(player.getFoodData().getFoodLevel(), player.getHealth(), player.getMaxHealth())
                && carried(player, player.getFoodData().getFoodLevel()) == null);
        if (meal != null) data.put("item_id", BuiltInRegistries.ITEM.getKey(meal.item).toString());
        if (failure != null) data.put("failure_code", failure);
        return Map.copyOf(data);
    }
}
