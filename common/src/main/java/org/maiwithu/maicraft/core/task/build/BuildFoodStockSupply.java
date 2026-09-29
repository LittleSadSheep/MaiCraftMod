// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import org.maiwithu.maicraft.core.inventory.FoodMaterialBudget;
import org.maiwithu.maicraft.core.inventory.OrdinaryFood;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskState;

/** 自动备餐只查询一次无线现货并按食物属性选取；不用固定食物名单，不委托合成，也不为取口粮远行开箱。 */
final class BuildFoodStockSupply {
    enum Status { RUNNING, READY, UNAVAILABLE, UNCERTAIN }
    private final Predicate<LocalPlayer> access;
    private final Function<LocalPlayer, Optional<StockEvidence.Snapshot>> stocks;
    private final BiFunction<LocalPlayer, Ae2SupplyTaskRecord, Task> factory;
    private Task child;
    private Ae2SupplyTaskRecord record;
    private boolean observed;
    private long retryAt, deadline, pausedAt = -1;
    private int serial;
    private Status settled;
    private Map<String, Object> evidence = Map.of();

    BuildFoodStockSupply() {
        this(p -> Ae2ResourceSupply.available() && Ae2ResourceSupply.hasCarriedWirelessTerminal(p),
                StockEvidence::latestNetwork, TaskFactory::create);
    }
    BuildFoodStockSupply(Predicate<LocalPlayer> access, Function<LocalPlayer, Optional<StockEvidence.Snapshot>> stocks,
            BiFunction<LocalPlayer, Ae2SupplyTaskRecord, Task> factory) {
        this.access = access; this.stocks = stocks; this.factory = factory;
    }
    boolean active() { return child != null; }
    boolean canAttempt(LocalPlayer player, BuildTaskRecord owner, FoodMaterialBudget budget) {
        if (active()) return true;
        if (!budget.complete() || settled == Status.UNCERTAIN || player.level().getGameTime() < retryAt || !access.test(player)) return false;
        var policy = owner.toolSupply();
        if (policy.policy() == MaterialPolicy.INVENTORY_ONLY) return false;
        var sources = SemanticMaterialSupplyCoordinator.resolveSources(policy.policy(), policy.sources());
        if (!sources.contains(Source.WIRELESS) && !sources.contains(Source.STORAGE)) return false;
        // 已观察到的空货架和“还不知道库存”不同；前者不反复打开 AE，后者允许一次真实查询。
        return stocks.apply(player).map(stock -> choice(player, stock, budget) != null).orElse(true);
    }

    Status tick(LocalPlayer player, BuildTaskRecord owner, FoodMaterialBudget budget, Function<Task, TaskState> runner) {
        long now = player.level().getGameTime();
        if (pausedAt >= 0) { deadline += now - pausedAt; pausedAt = -1; }
        if (child == null && (settled == Status.UNCERTAIN || now < retryAt)) return settled;
        if (child != null) {
            TaskState state;
            if (now >= deadline) { child.stop(player, Task.StopReason.REPLACED); state = TaskState.TIMEOUT; }
            else state = runner.apply(child);
            if (state == null || !state.isTerminal()) return Status.RUNNING;
            var result = child.result(state); var data = result == null || result.data() == null ? Map.<String, Object>of() : result.data();
            boolean supply = record.request.operation() == Ae2ResourceSupply.Operation.SUPPLY;
            evidence = Map.of("operation", record.request.operation().name(), "terminal_state", state.name(), "native_result", data);
            child = null; record = null;
            if (Boolean.TRUE.equals(data.get("outcome_uncertain")) || state == TaskState.TIMEOUT || state == TaskState.CANCELLED)
                return finish(player, Status.UNCERTAIN);
            if (state != TaskState.SUCCESS || result == null || !result.success()) return finish(player, Status.UNAVAILABLE);
            if (supply) return finish(player, OrdinaryFood.choose(player.getInventory().items, player.getFoodData().getFoodLevel(), budget) != null
                    ? Status.READY : Status.UNAVAILABLE);
            // 查询完成且界面收好后下一刻再选食物，不在同刻把预测库存当作背包物品。
            observed = true; return Status.RUNNING;
        }
        if (settled != null) { settled = null; observed = false; }
        var stock = stocks.apply(player);
        if (stock.isEmpty()) {
            if (observed) return finish(player, Status.UNAVAILABLE);
            start(player, owner, new Ae2ResourceSupply.Request(List.of(), false, Ae2ResourceSupply.Operation.OBSERVE));
            return Status.RUNNING;
        }
        var choice = choice(player, stock.orElseThrow(), budget);
        if (choice == null) return finish(player, Status.UNAVAILABLE);
        var id = BuiltInRegistries.ITEM.getKey(choice.item());
        start(player, owner, new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(id, choice.count())),
                false, Ae2ResourceSupply.Operation.SUPPLY, true));
        return Status.RUNNING;
    }
    private OrdinaryFood.StockChoice choice(LocalPlayer player, StockEvidence.Snapshot stock, FoodMaterialBudget budget) {
        return OrdinaryFood.stock(stock.stored(), player.getInventory().items, player.getFoodData().getFoodLevel(), budget);
    }
    private void start(LocalPlayer player, BuildTaskRecord owner, Ae2ResourceSupply.Request request) {
        deadline = player.level().getGameTime() + 600;
        record = Ae2ResourceSupply.taskRecord(owner.getToolCallId() + "/food-stock-" + (++serial), deadline, request);
        child = factory.apply(player, record);
    }
    private Status finish(LocalPlayer player, Status status) {
        settled = status; retryAt = player.level().getGameTime() + 1200; return status;
    }
    void stop(LocalPlayer player) {
        // 被打断的取物先收尾；不确定时保留禁止重发标志，即使背包已有预测物品也不能继续吃。
        if (child != null) {
            child.stop(player, Task.StopReason.REPLACED);
            var result = child.result(TaskState.CANCELLED);
            evidence = result == null || result.data() == null ? Map.of() : result.data();
            child = null; record = null; finish(player, Status.UNCERTAIN);
        }
    }
    /** 防卫只暂停同一笔供料，不结算成取消后重发；恢复时补回让出身体期间的等待预算。 */
    void pause(LocalPlayer player) {
        if (child != null) child.stop(player, Task.StopReason.PREEMPTED);
        if (pausedAt < 0) pausedAt = player.level().getGameTime();
    }
    Map<String, Object> progress() {
        return Map.of("active", active(), "status", settled == null ? "pending" : settled.name(), "receipt", evidence);
    }
}
