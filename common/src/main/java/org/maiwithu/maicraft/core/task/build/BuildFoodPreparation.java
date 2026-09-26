// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private final List<Map<String, Object>> receipts = new ArrayList<>();
    private Task child;
    private EatItemTaskRecord meal;
    private boolean maintaining, recovery, uncertain;
    private long deadline, restStarted = -1, healthProgress;
    private int serial, episodeMeals, confirmedItems, confirmedMeals, countBefore, foodBefore;
    private float healthBefore, lastHealth;
    private String failure, message;
    private FailureType failureType = FailureType.UNKNOWN;

    BuildFoodPreparation() { this(EatCompanionTask::new); }
    BuildFoodPreparation(BiFunction<LocalPlayer, EatItemTaskRecord, Task> eater) { this.eater = eater; }

    static boolean needs(boolean creative, int food, float health, float maxHealth) {
        // 受伤但饱食度十七时也不能自然回血，不能等到快饿或只剩六点生命才吃；健康身体仍沿用普通补食阈值。
        return !creative && (food <= 14 || health < Math.min(8, maxHealth) || food < 18 && health < maxHealth);
    }
    /** 饥饿已影响疾跑或生命低于恢复底线时必须停工；普通补食需求不等同于无法继续施工。 */
    private static boolean urgent(int food, float health, float maxHealth) { return food <= 6 || health < Math.min(8, maxHealth); }
    boolean active() { return maintaining || child != null; }
    boolean shouldPrepare(LocalPlayer player) {
        if (failure != null || active()) return true;
        if (player.getAbilities().instabuild) return false;
        int food = player.getFoodData().getFoodLevel(); float health = player.getHealth(), max = player.getMaxHealth();
        // 缺少普通食物但仍能安全工作时，不反复停导航，也不拦住用于恢复供给的工作台准备。
        return needs(false, food, health, max) && (urgent(food, health, max) || choose(player.getInventory().items, food) != null);
    }
    String failure() { return failure; }
    String message() { return message; }
    FailureType failureType() { return failureType; }
    long deadline() { return deadline; }
    List<Map<String, Object>> receipts() { return List.copyOf(receipts); }

    Status tick(LocalPlayer player, BuildTaskRecord owner, Function<Task, TaskState> runner) {
        if (failure != null) return Status.FAILED;
        if (player.getAbilities().instabuild) { stop(player); return Status.READY; }
        long now = player.level().getGameTime();
        int food = player.getFoodData().getFoodLevel(); float health = player.getHealth();
        if (health <= 0) return fail("build_body_not_alive", "Construction stopped because the body is no longer alive", FailureType.UNKNOWN);
        if (!maintaining) {
            if (!needs(false, food, health, player.getMaxHealth())) return Status.READY;
            if (!urgent(food, health, player.getMaxHealth()) && choose(player.getInventory().items, food) == null) return Status.READY;
            // 长任务先松开施工动作，再吃到接近饱；危急低血量多补到二十，给真实自然恢复留出条件。
            maintaining = true; recovery = health < Math.min(8, player.getMaxHealth()); episodeMeals = 0;
            deadline = now + EPISODE_TIMEOUT; restStarted = -1; lastHealth = health; healthProgress = now;
        }
        recovery |= health < Math.min(8, player.getMaxHealth());
        if (now >= deadline) { interruptMeal(player, TaskState.TIMEOUT); return fail("build_food_timeout", "Food preparation exceeded its bounded time", FailureType.UNKNOWN); }
        if (child != null) {
            TaskState terminal;
            if (now >= meal.getDeadlineGameTime()) { child.stop(player, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT; }
            else terminal = runner.apply(child);
            if (terminal == null) return Status.RUNNING;
            boolean consumed = finishMeal(player, terminal);
            if (!consumed) return fail("build_food_unconfirmed", "The native food action did not confirm one consumed item and increased hunger", FailureType.UNKNOWN);
            return Status.RUNNING; // 吃完和继续施工分开一刻，先让持用和必要的背包界面完整收尾。
        }
        // 受伤时本次补到二十，给真实自然恢复留余量；这里只吃已有食物，不直接写生命值，也不要求普通伤势等到满血。
        int targetFood = recovery || health < player.getMaxHealth() ? 20 : 18;
        if (food < targetFood) {
            Item chosen = choose(player.getInventory().items, food);
            if (chosen == null) {
                // 补食中途耗尽也按当前身体判断；已经越过低生命/低饥饿底线，就将角色交回原工作。
                if (!urgent(food, health, player.getMaxHealth())) { maintaining = false; recovery = false; restStarted = -1; return Status.READY; }
                return fail("build_food_unavailable", "Construction paused: critical hunger or health requires food, but no ordinary carried food is available", FailureType.NO_MATERIAL);
            }
            if (++episodeMeals > 32) return fail("build_food_action_limit", "Food preparation exhausted its bounded meal count", FailureType.UNKNOWN);
            countBefore = PlayerInv.count(player.getInventory(), chosen); foodBefore = food; healthBefore = health;
            String id = BuiltInRegistries.ITEM.getKey(chosen).toString();
            meal = new EatItemTaskRecord(owner.getToolCallId() + "/build-food-" + (++serial), now + MEAL_TIMEOUT, chosen, id);
            child = eater.apply(player, meal);
            return Status.RUNNING;
        }
        if (recovery && health < Math.min(8, player.getMaxHealth())) {
            // 已吃够才停下来等血量，看到真实恢复才刷新等待期限；受伤或长时间不回血就停工，不原地死等。
            if (restStarted < 0) { restStarted = now; healthProgress = now; lastHealth = health; }
            if (health < lastHealth) return fail("build_health_declining", "Health declined while resting after food; construction remains stopped", FailureType.UNKNOWN);
            if (health > lastHealth) { lastHealth = health; healthProgress = now; }
            if (now - healthProgress >= RECOVERY_IDLE_TIMEOUT || now - restStarted >= RECOVERY_TIMEOUT)
                return fail("build_health_recovery_unconfirmed", "Natural health recovery did not reach a safe level within the bounded wait", FailureType.UNKNOWN);
            return Status.RUNNING;
        }
        maintaining = false; recovery = false; return Status.READY;
    }

    // 自动保养只选可操作的前三十六格；同 ID 任一叠带特殊效果就整类排除，防止旧进食执行器按 ID 拿错叠。
    static Item choose(List<ItemStack> inventory, int food) {
        Item best = null; double score = Double.NEGATIVE_INFINITY;
        var unsafe = new HashSet<Item>();
        for (int slot = 0; slot < Math.min(36, inventory.size()); slot++) {
            ItemStack stack = inventory.get(slot); if (!stack.isEmpty() && !ordinary(stack)) unsafe.add(stack.getItem());
        }
        for (int slot = 0; slot < Math.min(36, inventory.size()); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty() || unsafe.contains(stack.getItem()) || !ordinary(stack)) continue;
            var nutrition = stack.get(DataComponents.FOOD);
            double value = Math.min(20 - food, nutrition.nutrition()) * 4.0 + nutrition.saturation() * 2.0;
            if (value > score) { score = value; best = stack.getItem(); }
        }
        return best;
    }

    private static boolean ordinary(ItemStack stack) {
        if (stack.is(Items.CHORUS_FRUIT) || stack.is(Items.SUSPICIOUS_STEW) || stack.is(Items.HONEY_BOTTLE)) return false;
        var food = stack.get(DataComponents.FOOD);
        var baseline = new ItemStack(stack.getItem()).get(DataComponents.FOOD);
        return food != null && food.equals(baseline) && food.nutrition() > 0 && food.effects().isEmpty()
                && !stack.has(DataComponents.POTION_CONTENTS) && !stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS);
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
        interruptMeal(player, TaskState.CANCELLED); maintaining = false; recovery = false; restStarted = -1;
    }
    private void interruptMeal(LocalPlayer player, TaskState terminal) {
        if (child != null) { child.stop(player, Task.StopReason.REPLACED); finishMeal(player, terminal); }
    }
    private Status fail(String code, String detail, FailureType type) {
        failure = code; message = detail; failureType = type; maintaining = false; return Status.FAILED;
    }
    Map<String, Object> progress(LocalPlayer player) {
        var data = new LinkedHashMap<String, Object>();
        data.put("phase", failure != null ? "failed" : child != null ? "eating" : maintaining ? "recovering_or_refilling" : "ready");
        data.put("confirmed_food_items", confirmedItems); data.put("confirmed_food_actions", confirmedMeals);
        data.put("food", player.getFoodData().getFoodLevel()); data.put("health", player.getHealth()); data.put("outcome_uncertain", uncertain);
        // 回执区分缺少补食与紧急停工，不将继续施工误报为已吃饱或已恢复生命。
        data.put("refill_deferred", !player.getAbilities().instabuild && !active()
                && needs(false, player.getFoodData().getFoodLevel(), player.getHealth(), player.getMaxHealth())
                && !urgent(player.getFoodData().getFoodLevel(), player.getHealth(), player.getMaxHealth())
                && choose(player.getInventory().items, player.getFoodData().getFoodLevel()) == null);
        if (meal != null) data.put("item_id", BuiltInRegistries.ITEM.getKey(meal.item).toString());
        if (failure != null) data.put("failure_code", failure);
        return Map.copyOf(data);
    }
}
