// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.inventory.InventoryKeepPlan;
import org.maiwithu.maicraft.core.inventory.InventoryWorkItems;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.task.build.InventoryDepositCoordinator;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 背包吃紧时把闲置物资存到随身AE；原生动作仍由取料任务的同一子任务生命周期推进。 */
final class AcquisitionInventoryTidy {
    record Outcome(boolean uncertain, boolean progressed) {}
    private final List<Map<String, Object>> history = new ArrayList<>();
    private final UnaryOperator<Set<Item>> reservations;
    private Map<ResourceLocation, Integer> approved = Map.of(), before = Map.of(), lastUnchanged = Map.of();
    private Map<ResourceLocation, String> retained = Map.of();
    private TaskRecord pending;
    private boolean effects;
    private Outcome outcome = new Outcome(false, false);

    AcquisitionInventoryTidy() { this(InventoryWorkItems::current); }
    /** 保留清单读取独立于存取执行，离线重放可提供同一份已声明工作材料。 */
    AcquisitionInventoryTidy(UnaryOperator<Set<Item>> reservations) { this.reservations = reservations; }

    TaskRecord prepare(LocalPlayer player, Set<Item> requested, String callId, long deadline, boolean wireless) {
        // 一个取料调用最多整理四轮；相同清单若未腾出空间，不重复打开终端制造无进展循环。
        if (pending != null || history.size() >= 4 || !wireless || !closed(player)) return null;
        var plan = InventoryKeepPlan.inspect(player.getInventory().items,
                reservations.apply(requested), ScaffoldMaterials.of(player));
        if (plan.deposit().isEmpty() || plan.deposit().equals(lastUnchanged)) return null;
        approved = plan.deposit(); retained = plan.retainedReasons(); before = counts(player, approved.keySet());
        var ordered = new LinkedHashMap<ResourceLocation, Integer>();
        // 先整叠存入腾出中转槽，再处理必须保留一部分的物品，防止满包时第一步就卡在分堆。
        approved.entrySet().stream().sorted(Comparator.comparing(entry -> !hasWholeStack(player, entry.getKey(), entry.getValue())))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        pending = Ae2ResourceSupply.taskRecord(callId + "-inventory-tidy-" + history.size(), deadline,
                InventoryDepositCoordinator.aeDepositRequest(ordered, true));
        return pending;
    }

    boolean owns(TaskRecord record) { return pending != null && pending == record; }
    Outcome outcome() { return outcome; }
    boolean effectsObserved() { return effects; }
    List<Map<String, Object>> history() { return List.copyOf(history); }

    void settle(LocalPlayer player, TaskState state, TaskResult result) {
        Map<String, Object> data = result == null || result.data() == null ? Map.of() : result.data();
        Map<ResourceLocation, Integer> moved = Map.of(); boolean invalid = false;
        try { moved = InventoryDepositCoordinator.verifiedAeCounts(data, approved); }
        catch (IllegalArgumentException missing) { invalid = state == TaskState.SUCCESS || Boolean.TRUE.equals(data.get("effects_started")); }
        // 只累计原生收纳回执，并核对主背包差量；并发拾取或未结鼠标栈都不能凭数量猜作存入成功。
        var after = counts(player, approved.keySet());
        for (var item : approved.keySet()) if (before.get(item) - after.get(item) != moved.getOrDefault(item, 0)) invalid = true;
        boolean uncertain = invalid || Boolean.TRUE.equals(data.get("outcome_uncertain")) || !closed(player)
                || state == TaskState.CANCELLED || state == TaskState.TIMEOUT;
        int total = moved.values().stream().mapToInt(Integer::intValue).sum();
        effects |= total > 0 || Boolean.TRUE.equals(data.get("effects_started")) || !before.equals(after);
        outcome = new Outcome(uncertain, total > 0);
        var row = new LinkedHashMap<String, Object>();
        row.put("storage", "ae2_wireless"); row.put("approved_deposit", strings(approved));
        row.put("confirmed_deposited", strings(moved)); row.put("retained_reasons", strings(retained));
        row.put("outcome_uncertain", uncertain); row.put("terminal_state", state.name().toLowerCase(Locale.ROOT));
        row.put("empty_main_slots_after", player.getInventory().items.stream().limit(36).filter(ItemStack::isEmpty).count());
        if (data.containsKey("failure_code")) row.put("cause_code", data.get("failure_code"));
        history.add(Map.copyOf(row)); lastUnchanged = total == 0 ? approved : Map.of(); pending = null;
    }

    private static boolean closed(LocalPlayer player) {
        return player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried() != null
                && player.inventoryMenu.getCarried().isEmpty() && Minecraft.getInstance().screen == null;
    }
    private static boolean hasWholeStack(LocalPlayer player, ResourceLocation id, int amount) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return player.getInventory().items.stream().limit(36).anyMatch(stack -> stack.is(item) && stack.getCount() <= amount);
    }
    private static Map<ResourceLocation, Integer> counts(LocalPlayer player, Set<ResourceLocation> items) {
        var result = new LinkedHashMap<ResourceLocation, Integer>();
        items.forEach(id -> result.put(id, PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(id))));
        return Map.copyOf(result);
    }
    private static Map<String, Object> strings(Map<ResourceLocation, ?> values) {
        var result = new LinkedHashMap<String, Object>(); values.forEach((id, value) -> result.put(id.toString(), value)); return Map.copyOf(result);
    }
}
