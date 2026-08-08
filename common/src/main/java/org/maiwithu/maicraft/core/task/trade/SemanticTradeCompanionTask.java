// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * First-person, receipt-driven vanilla merchant executor.
 *
 * <p>The record names only a final inventory fact and semantic safety policy. Runtime entity ids,
 * paths, offer indices and menu slots never leave this internal task.
 */
public final class SemanticTradeCompanionTask
        extends AbstractCompanionTask<SemanticTradeTaskRecord> {
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final long MENU_WAIT_TICKS = 80L;

    private enum Phase {
        SURVEY, OPEN, WAIT_MENU, SELECT, PAY, TAKE, CLEANUP, COMPLETE
    }

    private enum Purpose {
        OPEN, PAY, TAKE, CLEAN_A, CLEAN_B, CLOSE
    }

    private record PaymentPlan(
            List<ContainerTransferTaskRecord.Move> moves,
            ItemStack paymentA,
            ItemStack paymentB,
            List<Map<String, Object>> paymentFacts) {}

    private record OfferPlan(
            MerchantOffer offer,
            int offerIndex,
            ItemStack result,
            int requiredTrades,
            int paymentUnits,
            PaymentPlan nextPayment) {}

    private static final class PaymentGroup {
        final ItemStack kind;
        int count;

        PaymentGroup(ItemStack kind, int count) {
            this.kind = kind.copyWithCount(1);
            this.count = count;
        }

        PaymentGroup copy() {
            return new PaymentGroup(kind, count);
        }
    }

    private Phase phase = Phase.SURVEY;
    private List<AbstractVillager> merchants = List.of();
    private int merchantCursor;
    private AbstractVillager merchant;
    private OfferPlan offerPlan;
    private Task activeChild;
    private Purpose activePurpose;
    private int childSerial;
    private boolean openedMenu;
    private boolean menuClaimed;
    private boolean nextAfterClose;
    private boolean finishRequested;
    private boolean effectsStarted;
    private boolean outcomeUncertain;
    private long waitMenuSince;
    private int initialOutputCount;
    private int completedTrades;
    private int inspectedMenus;
    private int protectedMerchantCount;
    private int outputBeforeTake;
    private final Map<String, Integer> observations = new LinkedHashMap<>();
    private final Set<ResourceLocation> observedPaymentItemCandidates = new LinkedHashSet<>();
    private List<Map<String, Object>> selectedPaymentFacts = List.of();
    private int selectedOutputPerTrade;
    private String selectedMerchantKind;
    private String failureCode;
    private String failureMessage;
    private FailureType failureType = FailureType.UNKNOWN;

    public SemanticTradeCompanionTask(
            LocalPlayer player, SemanticTradeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        initialOutputCount = outputCount();
    }

    @Override
    protected TaskState onTick() {
        if (outputCount() >= r.count && !finishRequested) {
            finishRequested = true;
            if (activeChild == null) phase = Phase.CLEANUP;
        }
        if (activeChild != null) return tickChild();
        if (phase == Phase.COMPLETE) {
            if (failureMessage != null) {
                fail(failureMessage, failureType);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        if (finishRequested && phase != Phase.CLEANUP) phase = Phase.CLEANUP;
        return switch (phase) {
            case SURVEY -> survey();
            case OPEN -> openNext();
            case WAIT_MENU -> waitMenu();
            case SELECT -> selectOffer();
            case PAY -> pay();
            case TAKE -> take();
            case CLEANUP -> cleanupMenu();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    private TaskState survey() {
        if (player.containerMenu != player.inventoryMenu) {
            return failFinal(
                    "menu_busy",
                    "A different synchronized menu is already open; MaiCraft will not close "
                            + "or repurpose it to begin trading.",
                    FailureType.UNKNOWN);
        }
        List<String> unknownLabels = r.protectedLabels.stream()
                .filter(label -> IntentRuntime.get().landmark(label) == null)
                .toList();
        if (!unknownLabels.isEmpty()) {
            return failFinal(
                    "unknown_protected_label",
                    "Some protected_labels are not remembered, so merchant safety cannot be proven.",
                    FailureType.UNKNOWN);
        }

        List<AbstractVillager> safe = new ArrayList<>();
        AABB bounds = player.getBoundingBox().inflate(r.radius);
        for (AbstractVillager candidate : player.clientLevel.getEntitiesOfClass(
                AbstractVillager.class, bounds,
                entity -> !entity.isRemoved() && entity.isAlive())) {
            if (!merchantKindMatches(candidate) || candidate.isBaby()) continue;
            List<String> reasons = protectionReasons(candidate);
            if (reasons.isEmpty()) safe.add(candidate);
            else protectedMerchantCount++;
        }
        safe.sort(Comparator.comparingDouble(player::distanceToSqr));
        merchants = List.copyOf(safe);
        if (merchants.isEmpty()) {
            return failFinal(
                    protectedMerchantCount > 0
                            ? "only_protected_loaded_merchants"
                            : "no_loaded_merchant",
                    protectedMerchantCount > 0
                            ? "Loaded matching merchants exist, but every one is named or inside "
                                    + "a protected remembered area."
                            : "No eligible villager or wandering trader is loaded inside the "
                                    + "bounded search radius.",
                    FailureType.TARGET_LOST);
        }
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    private boolean merchantKindMatches(AbstractVillager candidate) {
        if (!(candidate instanceof Villager) && !(candidate instanceof WanderingTrader)) {
            return false;
        }
        return switch (r.merchantKind) {
            case AUTO -> true;
            case VILLAGER -> candidate instanceof Villager;
            case WANDERING_TRADER -> candidate instanceof WanderingTrader;
        };
    }

    private List<String> protectionReasons(AbstractVillager candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate.hasCustomName()) reasons.add("named");
        String dimension = player.level().dimension().location().toString();
        for (String label : r.protectedLabels) {
            IntentRuntime.Landmark landmark = IntentRuntime.get().landmark(label);
            if (landmark != null
                    && insideLandmark(candidate.blockPosition(), landmark, dimension)) {
                reasons.add("protected_landmark");
            }
        }
        return List.copyOf(reasons);
    }

    private static boolean insideLandmark(
            BlockPos position, IntentRuntime.Landmark landmark, String dimension) {
        Goal.WorldPosition center = landmark.position();
        if (center.dimension() != null && !center.dimension().equals(dimension)) return false;
        long dx = (long) position.getX() - center.x();
        long dz = (long) position.getZ() - center.z();
        return dx * dx + dz * dz
                <= (long) LANDMARK_PROTECTION_RADIUS * LANDMARK_PROTECTION_RADIUS;
    }

    private TaskState openNext() {
        while (merchantCursor < merchants.size()) {
            merchant = merchants.get(merchantCursor++);
            if (!merchant.isAlive() || merchant.isRemoved()
                    || !merchantKindMatches(merchant)
                    || merchant.isBaby()
                    || !protectionReasons(merchant).isEmpty()) {
                note("merchant_became_unavailable");
                continue;
            }
            menuClaimed = false;
            return start(new InteractEntityTaskRecord(
                    childId("open"), childDeadline(2L * 60L * 20L),
                    MouseButton.RIGHT, merchant.getId(), 0, null), Purpose.OPEN);
        }
        return exhausted();
    }

    private TaskState waitMenu() {
        if (player.containerMenu instanceof MerchantMenu) {
            openedMenu = true;
            inspectedMenus++;
            phase = Phase.SELECT;
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() - waitMenuSince <= MENU_WAIT_TICKS) {
            return TaskState.RUNNING;
        }
        note("merchant_menu_not_opened");
        if (player.containerMenu != player.inventoryMenu) {
            openedMenu = true;
            nextAfterClose = true;
            phase = Phase.CLEANUP;
        } else {
            phase = Phase.OPEN;
        }
        return TaskState.RUNNING;
    }

    private TaskState selectOffer() {
        MerchantMenu menu = merchantMenu();
        if (menu == null) {
            openedMenu = false;
            note("merchant_menu_lost");
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        if (!menu.getSlot(0).getItem().isEmpty()
                || !menu.getSlot(1).getItem().isEmpty()
                || !menu.getSlot(2).getItem().isEmpty()) {
            if (!menuClaimed) {
                note("merchant_menu_not_empty");
                nextAfterClose = true;
                phase = Phase.CLEANUP;
                return TaskState.RUNNING;
            }
            return failFinal(
                    "merchant_state_diverged",
                    "The claimed merchant menu retained unexpected payment or result contents; "
                            + "blind continuation is unsafe.",
                    FailureType.UNKNOWN);
        }

        int missing = Math.max(0, r.count - outputCount());
        if (missing == 0) {
            finishRequested = true;
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }

        boolean matching = false;
        boolean inStock = false;
        boolean paymentAllowed = false;
        boolean enoughStock = false;
        boolean enoughCurrency = false;
        boolean unambiguous = false;
        boolean enoughSpace = false;
        List<OfferPlan> plans = new ArrayList<>();
        for (int index = 0; index < menu.getOffers().size(); index++) {
            MerchantOffer offer = menu.getOffers().get(index);
            ItemStack result = offer.getResult();
            if (result.isEmpty()
                    || !result.is(BuiltInRegistries.ITEM.get(r.itemId))) continue;
            matching = true;
            if (offer.isOutOfStock()) continue;
            inStock = true;
            observePaymentItems(offer);
            if (!paymentAllowed(offer)) continue;
            paymentAllowed = true;
            int trades = ceilDiv(missing, Math.max(1, result.getCount()));
            if (offer.getMaxUses() - offer.getUses() < trades) continue;
            enoughStock = true;
            PaymentPlan nextPayment = paymentPlan(menu, offer);
            if (nextPayment == null || !affordableForTrades(menu, offer, trades)) continue;
            enoughCurrency = true;
            MerchantOffer resolved = menu.getOffers().getRecipeFor(
                    nextPayment.paymentA, nextPayment.paymentB, 0);
            if (resolved != offer) continue;
            unambiguous = true;
            if (outputCapacity(result) < missing) continue;
            enoughSpace = true;
            plans.add(new OfferPlan(
                    offer, index, result.copy(), trades,
                    paymentUnits(offer), nextPayment));
        }
        if (plans.isEmpty()) {
            note(!matching ? "no_matching_offer"
                    : !inStock ? "matching_offer_out_of_stock"
                    : !paymentAllowed ? "payment_policy_rejected"
                    : !enoughStock ? "offer_stock_insufficient"
                    : !enoughCurrency ? "insufficient_payment"
                    : !unambiguous ? "ambiguous_offer_selection"
                    : !enoughSpace ? "inventory_space_required"
                    : "no_safe_offer");
            nextAfterClose = true;
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        offerPlan = plans.stream().min(Comparator
                .comparingInt((OfferPlan plan) ->
                        plan.paymentUnits * plan.requiredTrades)
                .thenComparingInt(OfferPlan::requiredTrades)
                .thenComparingInt(OfferPlan::offerIndex)).orElseThrow();
        selectedPaymentFacts = offerPlan.nextPayment.paymentFacts;
        selectedOutputPerTrade = offerPlan.result.getCount();
        selectedMerchantKind = merchant instanceof WanderingTrader
                ? "wandering_trader" : "villager";
        phase = Phase.PAY;
        return TaskState.RUNNING;
    }

    private record GroupChoice(int a, int b) {}

    private boolean paymentAllowed(MerchantOffer offer) {
        if (r.allowedPaymentIds.isEmpty()) {
            if (!offer.getCostA().getItem().equals(Items.EMERALD)) return false;
            ItemStack costB = offer.getCostB();
            return costB.isEmpty() || costB.is(Items.EMERALD);
        }
        Set<ResourceLocation> allowed = Set.copyOf(r.allowedPaymentIds);
        if (!allowed.contains(BuiltInRegistries.ITEM.getKey(
                offer.getCostA().getItem()))) return false;
        ItemStack costB = offer.getCostB();
        return costB.isEmpty()
                || allowed.contains(BuiltInRegistries.ITEM.getKey(costB.getItem()));
    }

    private void observePaymentItems(MerchantOffer offer) {
        observedPaymentItemCandidates.add(BuiltInRegistries.ITEM.getKey(
                offer.getCostA().getItem()));
        ItemStack second = offer.getCostB();
        if (!second.isEmpty()) {
            observedPaymentItemCandidates.add(BuiltInRegistries.ITEM.getKey(second.getItem()));
        }
    }

    private static int paymentUnits(MerchantOffer offer) {
        return offer.getCostA().getCount() + offer.getCostB().getCount();
    }

    private PaymentPlan paymentPlan(MerchantMenu menu, MerchantOffer offer) {
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        if (costA.isEmpty() || costA.getCount() > costA.getMaxStackSize()
                || !costB.isEmpty() && costB.getCount() > costB.getMaxStackSize()) {
            return null;
        }
        List<PaymentGroup> groups = paymentGroups(menu);
        GroupChoice choice = chooseGroups(
                groups, offer.getItemCostA(), costA.getCount(),
                offer.getItemCostB().orElse(null), costB.getCount());
        if (choice == null) return null;
        ItemStack kindA = groups.get(choice.a).kind;
        ItemStack kindB = choice.b < 0 ? ItemStack.EMPTY : groups.get(choice.b).kind;

        int[] available = new int[menu.slots.size()];
        for (int slot = 3; slot < Math.min(39, menu.slots.size()); slot++) {
            available[slot] = menu.getSlot(slot).getItem().getCount();
        }
        List<ContainerTransferTaskRecord.Move> moves = new ArrayList<>();
        if (!allocate(menu, available, kindA, costA.getCount(), 0, moves)) return null;
        if (!costB.isEmpty()
                && !allocate(menu, available, kindB, costB.getCount(), 1, moves)) {
            return null;
        }
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(Map.of(
                "item_id", BuiltInRegistries.ITEM.getKey(kindA.getItem()).toString(),
                "count_per_trade", costA.getCount()));
        if (!costB.isEmpty()) {
            facts.add(Map.of(
                    "item_id", BuiltInRegistries.ITEM.getKey(kindB.getItem()).toString(),
                    "count_per_trade", costB.getCount()));
        }
        return new PaymentPlan(
                List.copyOf(moves),
                kindA.copyWithCount(costA.getCount()),
                costB.isEmpty() ? ItemStack.EMPTY
                        : kindB.copyWithCount(costB.getCount()),
                List.copyOf(facts));
    }

    private boolean affordableForTrades(
            MerchantMenu menu, MerchantOffer offer, int trades) {
        List<PaymentGroup> groups = paymentGroups(menu);
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        ItemCost itemCostB = offer.getItemCostB().orElse(null);
        for (int trade = 0; trade < trades; trade++) {
            GroupChoice choice = chooseGroups(
                    groups, offer.getItemCostA(), costA.getCount(),
                    itemCostB, costB.getCount());
            if (choice == null) return false;
            groups.get(choice.a).count -= costA.getCount();
            if (choice.b >= 0) groups.get(choice.b).count -= costB.getCount();
        }
        return true;
    }

    private static GroupChoice chooseGroups(
            List<PaymentGroup> groups,
            ItemCost costA,
            int countA,
            ItemCost costB,
            int countB) {
        List<Integer> first = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            PaymentGroup group = groups.get(index);
            if (group.count >= countA && costA.test(group.kind)) first.add(index);
        }
        first.sort(Comparator.comparingInt(index -> groups.get(index).count));
        for (int a : first) {
            PaymentGroup groupA = groups.get(a);
            groupA.count -= countA;
            if (costB == null || countB <= 0) {
                groupA.count += countA;
                return new GroupChoice(a, -1);
            }
            int chosenB = -1;
            int bestCount = Integer.MAX_VALUE;
            for (int b = 0; b < groups.size(); b++) {
                PaymentGroup groupB = groups.get(b);
                if (groupB.count >= countB && groupB.count < bestCount
                        && costB.test(groupB.kind)) {
                    chosenB = b;
                    bestCount = groupB.count;
                }
            }
            groupA.count += countA;
            if (chosenB >= 0) return new GroupChoice(a, chosenB);
        }
        return null;
    }

    private static List<PaymentGroup> paymentGroups(MerchantMenu menu) {
        List<PaymentGroup> groups = new ArrayList<>();
        for (int slot = 3; slot < Math.min(39, menu.slots.size()); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            PaymentGroup match = null;
            for (PaymentGroup group : groups) {
                if (ItemStack.isSameItemSameComponents(group.kind, stack)) {
                    match = group;
                    break;
                }
            }
            if (match == null) groups.add(new PaymentGroup(stack, stack.getCount()));
            else match.count += stack.getCount();
        }
        return groups;
    }

    private static boolean allocate(
            MerchantMenu menu,
            int[] available,
            ItemStack kind,
            int count,
            int destination,
            List<ContainerTransferTaskRecord.Move> moves) {
        int remaining = count;
        for (int slot = 3;
                slot < Math.min(39, menu.slots.size()) && remaining > 0; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (available[slot] <= 0
                    || !ItemStack.isSameItemSameComponents(stack, kind)) continue;
            int moved = Math.min(remaining, available[slot]);
            moves.add(new ContainerTransferTaskRecord.Move(slot, destination, moved));
            available[slot] -= moved;
            remaining -= moved;
        }
        return remaining == 0;
    }

    private int outputCapacity(ItemStack result) {
        int capacity = 0;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) capacity += result.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stack, result)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return capacity;
    }

    private TaskState pay() {
        MerchantMenu menu = merchantMenu();
        if (menu == null) return menuLost(false);
        if (offerPlan == null
                || offerPlan.offerIndex < 0
                || offerPlan.offerIndex >= menu.getOffers().size()
                || menu.getOffers().get(offerPlan.offerIndex) != offerPlan.offer) {
            return failFinal(
                    "merchant_offers_changed",
                    "The synchronized merchant offers changed before payment; no trade was taken.",
                    FailureType.TARGET_LOST);
        }
        PaymentPlan payment = paymentPlan(menu, offerPlan.offer);
        if (payment == null) {
            return failFinal(
                    "payment_inventory_changed",
                    "The proven payment stacks are no longer present in the synchronized inventory.",
                    FailureType.NO_MATERIAL);
        }
        MerchantOffer resolved = menu.getOffers().getRecipeFor(
                payment.paymentA, payment.paymentB, 0);
        if (resolved != offerPlan.offer) {
            return failFinal(
                    "ambiguous_offer_selection",
                    "The payment tuple no longer resolves uniquely to the requested output without "
                            + "a dedicated offer-selection receipt.",
                    FailureType.UNKNOWN);
        }
        menuClaimed = true;
        selectedPaymentFacts = payment.paymentFacts;
        return start(new ContainerTransferTaskRecord(
                childId("pay"), childDeadline(2L * 60L * 20L),
                menu.containerId, payment.moves), Purpose.PAY);
    }

    private TaskState take() {
        MerchantMenu menu = merchantMenu();
        if (menu == null) return menuLost(true);
        ItemStack paymentA = menu.getSlot(0).getItem();
        ItemStack paymentB = menu.getSlot(1).getItem();
        ItemStack result = menu.getSlot(2).getItem();
        if (offerPlan == null
                || !offerPlan.offer.satisfiedBy(paymentA, paymentB)
                || result.isEmpty()
                || !result.is(BuiltInRegistries.ITEM.get(r.itemId))) {
            return failFinal(
                    "trade_result_unconfirmed",
                    "The synchronized merchant state did not prove the selected trade.",
                    FailureType.UNKNOWN);
        }
        outputBeforeTake = outputCount();
        effectsStarted = true;
        return start(new ContainerTransferTaskRecord(
                childId("take"), childDeadline(2L * 60L * 20L),
                menu.containerId,
                List.of(new ContainerTransferTaskRecord.Move(2, -1, 0))),
                Purpose.TAKE);
    }

    private TaskState cleanupMenu() {
        if (player.containerMenu == player.inventoryMenu) {
