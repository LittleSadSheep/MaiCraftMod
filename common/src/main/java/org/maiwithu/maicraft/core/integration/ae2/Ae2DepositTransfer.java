package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskState;

/** AE 存入只点真实玩家槽；尾数先原生分堆，再 Shift 精确小堆，不经服务端直接插入接口。 */
final class Ae2DepositTransfer {
    enum Status { RUNNING, SUCCEEDED, FAILED, UNCERTAIN }
    interface View {
        Object repository(AbstractContainerMenu menu);
        boolean connected(AbstractContainerMenu menu);
        List<Ae2ReflectionBridge.Entry> entries(AbstractContainerMenu menu);
        Map<Integer, Integer> playerSlots(AbstractContainerMenu menu, LocalPlayer player);
        boolean safeFallback(AbstractContainerMenu menu, ItemStack sample);
        default boolean passiveSlotChange(int slot, ItemStack before, ItemStack after) { return false; }
    }
    private final LocalPlayer player;
    private final Ae2ResourceSupply.Request request;
    private final AbstractContainerMenu menu;
    private final Screen screen;
    private final Object repository;
    private final View view;
    private final Set<Integer> reserved;
    private final Map<Integer, Integer> slots;
    private final Map<ResourceLocation, Integer> baseline;
    private final Ae2DepositLedger ledger = new Ae2DepositLedger();
    private Status status = Status.RUNNING;
    private String code = "ae2_deposit_running";
    private boolean effects, settleOnly, preserve;
    private int groupIndex, shifts, splitClicks, stagedSlot = -1, stagingSource = -1, batch, shiftSource;
    private long readySince, readyWaitStarted, stableTick = -1, networkBefore, verifiedNetworkAfter;
    private int stableAmount, stableCount, verifiedAmount;
    private Map<ResourceLocation, Long> readyStock = Map.of();
    private List<ItemStack> shiftBefore, stageBefore;
    private ResourceLocation item;
    private MenuReceipt receipt;
    private Task staging;
    private ContainerTransferTaskRecord stagingRecord;
    private boolean stagingStarted;

    Ae2DepositTransfer(LocalPlayerContext context, Ae2ResourceSupply.Request request, Ae2ReflectionBridge bridge,
                       Set<Integer> reserved, Map<ResourceLocation, Integer> baseline, Integer terminalSlot) {
        this(context, request, nativeView(bridge, terminalSlot), reserved, baseline);
    }
    Ae2DepositTransfer(LocalPlayerContext context, Ae2ResourceSupply.Request request, View view,
                       Set<Integer> reserved, Map<ResourceLocation, Integer> baseline) {
        player = context.player(); menu = player.containerMenu; screen = context.minecraft().screen;
        this.request = request; this.view = view; this.reserved = Set.copyOf(reserved); this.baseline = Map.copyOf(baseline);
        slots = view.playerSlots(menu, player); repository = view.repository(menu); readySince = readyWaitStarted = context.tickRevision();
        // 整份要求先核对可存普通堆，命名／带自定义组件的变种与临时停放的工具都不借用。
        for (var group : request.groups()) {
            int available = 0;
            for (int slot : slots.keySet()) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!reserved.contains(slot) && matches(stack, group.itemId())) available += stack.getCount();
            }
            if (available < group.count()) { fail("ae2_deposit_ordinary_stock_or_reserved_hand_shortfall", false); break; }
            if (!view.safeFallback(menu, BuiltInRegistries.ITEM.get(group.itemId()).getDefaultInstance())) {
                fail("ae2_deposit_other_slot_fallback_unsupported", false); break;
            }
        }
    }
    private static View nativeView(Ae2ReflectionBridge bridge, Integer terminalSlot) {
        var rules = new Ae2DepositMenu();
        return new View() {
            public Object repository(AbstractContainerMenu menu) { return bridge.repositoryIdentity(menu); }
            public boolean connected(AbstractContainerMenu menu) { return bridge.connected(menu); }
            public List<Ae2ReflectionBridge.Entry> entries(AbstractContainerMenu menu) { return bridge.entries(menu); }
            public Map<Integer, Integer> playerSlots(AbstractContainerMenu menu, LocalPlayer player) { return rules.playerSlots(menu, player); }
            public boolean safeFallback(AbstractContainerMenu menu, ItemStack sample) { return rules.safeFallback(menu, sample); }
            public boolean passiveSlotChange(int slot, ItemStack before, ItemStack after) {
                return terminalSlot != null && slot == terminalSlot && Ae2DepositAccess.sameTerminalIgnoringEnergy(before, after);
            }
        };
    }

    Status tick(LocalPlayerContext context) {
        if (status != Status.RUNNING) return status;
        if (!sameMenu(context)) { preserve = true; return fail("ae2_deposit_foreign_menu_or_repository", effects); }
        if (!view.connected(menu)) return fail("ae2_deposit_network_disconnected", effects || receipt != null);
        if (staging != null) return stage(context);
        if (receipt != null) return settleShift(context);
        if (!menu.getCarried().isEmpty()) return fail("ae2_deposit_foreign_cursor", effects);
        if (!inventoryMatchesConfirmed()) return fail("ae2_deposit_inventory_changed", effects);
        while (groupIndex < request.groups().size() && ledger.deposited(request.groups().get(groupIndex).itemId()) == request.groups().get(groupIndex).count()) groupIndex++;
        if (groupIndex == request.groups().size()) { status = Status.SUCCEEDED; code = "resources_deposited"; return status; }
        if (settleOnly) return fail("ae2_deposit_settled_before_full_request", false);
        if (context.tickRevision() - readyWaitStarted > 100) return fail("ae2_deposit_network_not_stable", false);
        var entries = view.entries(menu);
        if (entries == null) return fail("ae2_deposit_repository_unavailable", effects);
        Map<ResourceLocation, Long> visible = new LinkedHashMap<>();
        for (var group : request.groups()) visible.put(group.itemId(), Ae2DepositLedger.networkCount(entries, group.itemId()));
        // 给刚显示的终端至少两个不同刻稳定画面；这是客户端观察，不冒充 AE 没有提供的全量同步完成证书。
        if (!readyStock.equals(visible)) { readyStock = Map.copyOf(visible); readySince = context.tickRevision(); return status; }
        if (context.tickRevision() - readySince < 2) return status;
        var group = request.groups().get(groupIndex); item = group.itemId();
        int remaining = group.count() - ledger.deposited(item);
        int source = stagedSlot >= 0 ? stagedSlot : source(remaining);
        if (source < 0) return fail("ae2_deposit_source_unavailable", effects);
        ItemStack stack = player.getInventory().getItem(source);
        if (!matches(stack, item) || !menu.getSlot(slots.get(source)).mayPickup(player)) return fail("ae2_deposit_source_changed", effects);
        if (!view.safeFallback(menu, stack)) return fail("ae2_deposit_menu_slot_rules_changed", effects);
        if (stack.getCount() > remaining) {
            int destination = emptySlot();
            if (destination < 0) return fail("ae2_deposit_exact_tail_needs_empty_player_slot", effects);
            stagedSlot = destination; stagingSource = source; stageBefore = inventory(); stagingStarted = false;
            stagingRecord = new ContainerTransferTaskRecord("ae2-deposit-split-" + groupIndex,
                    player.level().getGameTime() + 1200, menu.containerId,
                    List.of(new ContainerTransferTaskRecord.Move(slots.get(source), slots.get(destination), remaining)), false);
            staging = TaskFactory.create(player, stagingRecord);
            return status;
        }
        if (!context.mutationAvailable()) return status;
        // 客户端 AE quickMove 不假扣背包，正常窗口点击包交给服务器的原生 Shift 路径处理。
        batch = stack.getCount(); shiftSource = source; shiftBefore = inventory(); networkBefore = visible.get(item);
        stableTick = -1; stableAmount = stableCount = verifiedAmount = 0; effects = true; shifts++;
        receipt = context.menus().click(context, slots.get(source), 0, ClickType.QUICK_MOVE, this::observeShift, 100);
        return status;
    }
    private Status stage(LocalPlayerContext context) {
        if (!stagingStarted) { staging.start(player); stagingStarted = true; }
        effects = true;
        if (player.level().getGameTime() >= stagingRecord.getDeadlineGameTime()) return fail("ae2_deposit_split_timeout", true);
        TaskState result = staging.tick(player);
        if (!result.isTerminal()) return status;
        var outcome = staging.result(result); staging = null;
        if (result != TaskState.SUCCESS || outcome == null || !outcome.success()) return fail("ae2_deposit_split_unconfirmed", true);
        if (outcome.data().get("confirmed_split_clicks") instanceof Number clicks) splitClicks += clicks.intValue();
        if (!unchangedOutside(stageBefore, Set.of(stagingSource, stagedSlot)) || !inventoryMatchesConfirmed()) return fail("ae2_deposit_split_inventory_changed", true);
        readyStock = Map.of(); readySince = readyWaitStarted = context.tickRevision();
        return status;
    }
    private MenuConfirmation.Verdict observeShift(LocalPlayerContext context, MenuReceipt ignored) {
        if (!sameMenu(context) || !view.connected(menu)) return MenuConfirmation.Verdict.DIVERGED;
        var entries = view.entries(menu);
        if (entries == null) return MenuConfirmation.Verdict.PENDING;
        ItemStack source = player.getInventory().getItem(shiftSource);
        if (!source.isEmpty() && !matches(source, item)) return MenuConfirmation.Verdict.DIVERGED;
        long after = Ae2DepositLedger.networkCount(entries, item);
        var observation = Ae2DepositLedger.observe(batch, source.getCount(), Ae2DepositLedger.inventory(shiftBefore).getOrDefault(item, 0),
                Ae2DepositLedger.inventory(inventory()).getOrDefault(item, 0), networkBefore, after, menu.getCarried().isEmpty(),
                unchangedOutside(shiftBefore, Set.of(shiftSource)));
        if (observation.verdict() == Ae2DepositLedger.Verdict.DIVERGED) return MenuConfirmation.Verdict.DIVERGED;
        if (observation.verdict() != Ae2DepositLedger.Verdict.CONFIRMED) { stableCount = 0; stableTick = -1; return MenuConfirmation.Verdict.PENDING; }
        if (stableAmount != observation.deposited()) { stableAmount = observation.deposited(); stableCount = 0; }
        if (stableTick != context.tickRevision()) { stableTick = context.tickRevision(); stableCount++; }
        if (stableCount < 2) return MenuConfirmation.Verdict.PENDING;
        verifiedAmount = observation.deposited(); verifiedNetworkAfter = after;
        return MenuConfirmation.Verdict.APPLIED;
    }
    private Status settleShift(LocalPlayerContext context) {
        receipt = context.menus().poll(context, receipt);
        if (!receipt.terminal()) return status;
        if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED || verifiedAmount <= 0) return fail("ae2_deposit_shift_unconfirmed", true);
        captureConfirmed(); readyWaitStarted = context.tickRevision();
        if (!inventoryMatchesConfirmed()) return fail("ae2_deposit_inventory_changed_after_receipt", true);
        if (verifiedAmount < batch) return fail("ae2_deposit_partial_network_capacity", false);
        return status;
    }
    private int source(int remaining) {
        int oversized = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (reserved.contains(slot) || !matches(player.getInventory().getItem(slot), item)) continue;
            if (player.getInventory().getItem(slot).getCount() <= remaining) return slot;
            if (oversized < 0) oversized = slot;
        }
        return oversized;
    }
    private int emptySlot() {
        for (int slot = 0; slot < 36; slot++) if (!reserved.contains(slot) && player.getInventory().getItem(slot).isEmpty()
                && menu.getSlot(slots.get(slot)).mayPlace(BuiltInRegistries.ITEM.get(item).getDefaultInstance())) return slot;
        return -1;
    }
    private boolean sameMenu(LocalPlayerContext context) {
        return context.player() == player && player.containerMenu == menu && context.minecraft().screen == screen
                && MenuVisibility.matches(context.minecraft(), menu) && view.repository(menu) == repository;
    }
    private List<ItemStack> inventory() {
        List<ItemStack> stacks = new ArrayList<>(); for (int i = 0; i < 36; i++) stacks.add(player.getInventory().getItem(i).copy()); return List.copyOf(stacks);
    }
    private boolean unchangedOutside(List<ItemStack> before, Set<Integer> changed) {
        for (int i = 0; i < 36; i++) if (!changed.contains(i) && !same(before.get(i), player.getInventory().getItem(i))
                && !view.passiveSlotChange(i, before.get(i), player.getInventory().getItem(i))) return false;
        return true;
    }
    boolean inventoryMatchesConfirmed() {
        var now = Ae2DepositLedger.inventory(inventory());
        for (var group : request.groups()) if (baseline.getOrDefault(group.itemId(), 0) - now.getOrDefault(group.itemId(), 0) != ledger.deposited(group.itemId())) return false;
        return true;
    }
    boolean complete() { return status == Status.SUCCEEDED && inventoryMatchesConfirmed(); }
    boolean effectsStarted() { return effects; }
    boolean pending() { return receipt != null || staging != null; }
    boolean preserveMenu() { return preserve || !menu.getCarried().isEmpty() || pending(); }
    String code() { return code; }
    Map<ResourceLocation, Integer> deposited() { return ledger.counts(); }
    void requestSettlement() { settleOnly = true; }
    void captureConfirmed() {
        // 回执已由菜单端确认时，只收取冻结的证明，不重新点击或重新推断网络已经接受多少。
        if (receipt != null && receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED && verifiedAmount > 0) {
            ledger.confirmed(item, verifiedAmount, networkBefore, verifiedNetworkAfter, batch, batch - verifiedAmount);
            receipt = null; stagedSlot = -1; readyStock = Map.of();
        }
    }
    void cancel(LocalPlayerContext context) {
        // 取消前可只读收取已经到达的确认，不能把刚确认入网的那一堆从回执里丢掉，也不再发下一次 Shift。
        if (receipt != null && sameMenu(context)) {
            try { settleShift(context); } catch (RuntimeException unavailable) { preserve = true; }
        }
        if (staging != null) {
            preserve = true;
            try { staging.stop(player, Task.StopReason.REPLACED); staging.result(TaskState.CANCELLED); }
            catch (RuntimeException unavailable) { preserve = true; }
            finally { staging = null; }
        }
        fail("ae2_deposit_cancelled", preserve || receipt != null || !menu.getCarried().isEmpty());
    }
    Map<String, Object> evidence() {
        Map<String, Object> result = new LinkedHashMap<>(ledger.evidence());
        result.put("deposit_shift_clicks_submitted", shifts); result.put("deposit_split_clicks_confirmed", splitClicks);
        result.put("deposit_outcome_uncertain", status == Status.UNCERTAIN || pending()); return Map.copyOf(result);
    }
    private Status fail(String code, boolean uncertain) {
        this.code = code; status = uncertain ? Status.UNCERTAIN : Status.FAILED;
        preserve |= uncertain || !menu.getCarried().isEmpty(); return status;
    }
    private static boolean matches(ItemStack stack, ResourceLocation item) { return Ae2DepositLedger.ordinary(stack) && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item); }
    private static boolean same(ItemStack a, ItemStack b) { return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b); }
}
