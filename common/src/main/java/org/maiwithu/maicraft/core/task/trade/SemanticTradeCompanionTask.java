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
 * 为了让背包达到指定物品数量，寻找商人、打开交易界面、挑报价、放付款物品、取结果，最后退还余款并关闭。
 * 已经有的物品会计入目标；只要求最终数量，不保证每一份都来自本轮交易。
 * 当前有单报价库存、默认报价选择和付款前空间等额外限制，具体例子见 A53～A56。
 */
public final class SemanticTradeCompanionTask
        extends AbstractCompanionTask<SemanticTradeTaskRecord> {
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final long MENU_WAIT_TICKS = 80L;
    private static final long TRADE_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;

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
    private TaskRecord activeRecord;
    private Purpose activePurpose;
    private int childSerial;
    private boolean openedMenu;
    private boolean openRequested;
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
    // 目标是背包最终至少有这么多物品，已经够了就请求收尾；正在执行的付款／收货子任务先结束。
    // 目前这个提前满足分支会绕过开头的菜单占用检查，再去关闭原本打开的界面，见 A56。
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

    // 只找已加载范围内的成年村民／流浪商人，按距离排列；先拒绝未知保护地标，避免错误理解保护范围。
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

    // 当前只要商人有自定义名字就全部排除；另外排除保护地标水平十二格内的商人，不看高度差。
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

    // 依次尝试候选商人，出手前重查它是否仍活着、成年并未受保护，再创建走近并右键的子任务。
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
            openRequested = true;
            return start(new InteractEntityTaskRecord(
                    childId("open"), childDeadline(2L * 60L * 20L),
                    MouseButton.RIGHT, merchant.getId(), 0, null), Purpose.OPEN);
        }
        return exhausted();
    }

    // 等待商人菜单出现并实际显示；超出等待窗口后尝试收尾并换下一位商人。
    private TaskState waitMenu() {
        if (player.containerMenu instanceof MerchantMenu) {
            openedMenu = true;
            var context = ClientRuntime.requireContext(player);
            if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
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

    // 从卖出目标物品的报价中筛选库存、付款许可、可支付数量、报价选择和背包空间。
    // 当前每个报价都必须独自满足全部缺额，不能把两项各十份的报价合起来凑二十份，见 A53。
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
            // 这里用默认选择去匹配支付组合，没有向原版选择具体报价；同价商品在后面时可能一直被前面商品盖住（A54）。
            MerchantOffer resolved = menu.getOffers().getRecipeFor(
                    nextPayment.paymentA, nextPayment.paymentB, 0);
            if (resolved != offer) continue;
            unambiguous = true;
            // 空间按付款前的背包计算，没有把即将移出的支付物品腾出的格算进去（A55）。
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
        // 在通过检查的报价中，按总支付件数、交易次数、报价下标排序；不同物品的件数没有换算成同一种价值。
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

    // 没明确列出支付物品时只允许绿宝石；给了列表后，报价的两种支付物品都必须在名单中。
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

    // 给下一笔交易凑出两格支付物品。相同物品但组件不同会分组，避免把无法堆叠的物品混放到同一支付格。
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

    // 在分组数量副本上反复扣除每笔成本，判断按当前这一个报价能否付完整个缺额；不真的扣背包。
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

    // 先从够付第一格的组里挑较小组，再尝试第二格；两格用同组时先临时扣除第一格数量，避免重复计算。
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

    // 商人菜单第 3～38 格对应玩家主背包和快捷栏；按物品及组件完全相同来汇总，不包含副手或盔甲。
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

    // 把一笔要付的数量分配到具体背包来源格，生成搬入商人支付格的步骤；available 防止两种成本重复使用同一份物品。
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

    // 出钱前确认还是原来那条报价，并重新查背包可支付物品；当前仍依赖默认报价匹配，没有真正选择 offerIndex。
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
                menu.containerId, payment.moves, false), Purpose.PAY);
    }

    // 只有支付格和结果格看起来满足所选交易，才从结果槽快速取出；取出后还要检查背包目标物品确实增加。
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
                List.of(new ContainerTransferTaskRecord.Move(2, -1, 0)), false),
                Purpose.TAKE);
    }

    // 已认领的商人菜单先把两格剩余付款物品收回，再关闭。
    // 其他当前界面也会落到关闭分支，没有检查 openedMenu／openRequested，因此已有目标物品也可能触发无关关闭（A56）。
    private TaskState cleanupMenu() {
        if (player.containerMenu == player.inventoryMenu && ClientRuntime.requireContext(player).minecraft().screen == null) {
            openedMenu = false;
            return afterClosed();
        }
        if (player.containerMenu instanceof MerchantMenu menu && menuClaimed) {
            if (!menu.getSlot(0).getItem().isEmpty()) {
                return start(new ContainerTransferTaskRecord(
                        childId("return-a"), childDeadline(2L * 60L * 20L),
                        menu.containerId,
                        List.of(new ContainerTransferTaskRecord.Move(0, -1, 0)), false),
                        Purpose.CLEAN_A);
            }
            if (!menu.getSlot(1).getItem().isEmpty()) {
                return start(new ContainerTransferTaskRecord(
                        childId("return-b"), childDeadline(2L * 60L * 20L),
                        menu.containerId,
                        List.of(new ContainerTransferTaskRecord.Move(1, -1, 0)), false),
                        Purpose.CLEAN_B);
            }
        }
        return start(new CloseMenuTaskRecord(
                childId("close"), childDeadline(30L * 20L)), Purpose.CLOSE);
    }

    // 关闭后清掉本次商人和报价状态：有失败就结束，物品够了就成功，否则按计划尝试下一位商人。
    private TaskState afterClosed() {
        openRequested = false;
        menuClaimed = false;
        offerPlan = null;
        merchant = null;
        if (failureMessage != null) {
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        if (finishRequested || outputCount() >= r.count) {
            finishRequested = true;
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        if (nextAfterClose) {
            nextAfterClose = false;
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        return failFinal(
                "trade_cleanup_state_unknown",
                "The merchant menu closed without a terminal or next-merchant state.",
                FailureType.INTERNAL);
    }

    // 把开交易界面、付款、拿结果、退还余款和关闭分别交给子任务；拿结果不确定时不盲目再买一次。
    private TaskState tickChild() {
        TaskState terminal = runChild(activeChild);
        if (terminal == null) {
            if (activeRecord != null) r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
            return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        Purpose purpose = activePurpose;
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        boolean success = terminal == TaskState.SUCCESS
                && result != null && result.success();
        if (!success) {
            switch (purpose) {
                case OPEN -> {
                    note("merchant_interaction_failed");
                    if (player.containerMenu != player.inventoryMenu) {
                        openedMenu = true;
                        nextAfterClose = true;
                        phase = Phase.CLEANUP;
                    } else {
                        phase = Phase.OPEN;
                    }
                    return TaskState.RUNNING;
                }
                case TAKE -> {
                    outcomeUncertain = true;
                    return failFinal(
                            "trade_take_unconfirmed",
                            "The synchronized result transfer was not confirmed; blind retry "
                                    + "could purchase twice.",
                            lastFailure());
                }
                case PAY -> {
                    outcomeUncertain = player.containerMenu != player.inventoryMenu;
                    return failFinal(
                            "trade_payment_unconfirmed",
                            "The synchronized payment transfer was not confirmed.",
                            lastFailure());
                }
                case CLEAN_A, CLEAN_B -> {
                    outcomeUncertain = true;
                    return failFinal(
                            "trade_payment_return_unconfirmed",
                            "Payment left in the merchant menu could not be confirmed back in "
                                    + "the main inventory.",
                            lastFailure());
                }
                case CLOSE -> {
                    outcomeUncertain = true;
                    if (failureMessage == null) {
                        failureCode = "merchant_menu_close_unconfirmed";
                        failureMessage = "The merchant menu close was not confirmed.";
                        failureType = lastFailure();
                    }
                    phase = Phase.COMPLETE;
                    return TaskState.RUNNING;
                }
            }
        }

        switch (purpose) {
            case OPEN -> {
                waitMenuSince = player.level().getGameTime();
                phase = Phase.WAIT_MENU;
            }
            case PAY -> phase = Phase.TAKE;
            case TAKE -> {
                int observed = outputCount();
                if (observed <= outputBeforeTake) {
                    outcomeUncertain = true;
                    return failFinal(
                            "trade_inventory_delta_missing",
                            "The confirmed trade result completed without the requested item count "
                                    + "increasing in the real main inventory.",
                            FailureType.UNKNOWN);
                }
                // 只观察目标物品总数有增长就记一笔完成；没有在这里核对完整付款消耗或准确产出数量。
                completedTrades++;
                r.extendDeadlineTo(player.level().getGameTime() + TRADE_PROGRESS_LEASE_TICKS);
                effectsStarted = false;
                if (observed >= r.count) {
                    finishRequested = true;
                    phase = Phase.CLEANUP;
                } else {
                    offerPlan = null;
                    phase = Phase.SELECT;
                }
            }
            case CLEAN_A, CLEAN_B -> phase = Phase.CLEANUP;
            case CLOSE -> {
                openedMenu = false;
                return afterClosed();
            }
        }
        if (finishRequested && phase != Phase.CLEANUP) phase = Phase.CLEANUP;
        return TaskState.RUNNING;
    }

    private TaskState start(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        r.extendDeadlineTo(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    private TaskState menuLost(boolean afterPayment) {
        outcomeUncertain |= afterPayment || effectsStarted || menuClaimed;
        openedMenu = false;
        return failFinal(
                "merchant_menu_lost",
                afterPayment
                        ? "The synchronized merchant menu changed after payment was prepared."
                        : "The synchronized merchant menu changed before the trade completed.",
                FailureType.TARGET_LOST);
    }

    // 所有候选都试完后，从已观察的原因中选一个主要解释；缺钱、没空间、没货与付款许可不同，不统一叫找不到商人。
    private TaskState exhausted() {
        if (outputCount() >= r.count) {
            finishRequested = true;
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        String code;
        String message;
        if (observations.containsKey("insufficient_payment")) {
            code = "insufficient_payment";
            message = "Loaded merchants offer the requested item, but the real main inventory "
                    + "cannot fund enough complete trades.";
        } else if (observations.containsKey("inventory_space_required")) {
            code = "inventory_space_required";
            message = "A matching affordable trade exists, but free main-inventory capacity "
                    + "cannot be proven for the requested final count.";
        } else if (observations.containsKey("offer_stock_insufficient")
                || observations.containsKey("matching_offer_out_of_stock")) {
            code = "trade_stock_insufficient";
            message = "Matching loaded offers cannot supply the requested final count before "
                    + "their synchronized stock is exhausted.";
        } else if (observations.containsKey("payment_policy_rejected")) {
            code = "payment_policy_rejected";
            message = "Matching loaded offers require payment items outside the allowed policy.";
        } else if (observations.containsKey("ambiguous_offer_selection")) {
            code = "ambiguous_offer_selection";
            message = "A matching offer shares its payment tuple with another offer; the current "
                    + "receipt path cannot prove which result the server will choose.";
        } else if (observations.containsKey("no_matching_offer")) {
            code = "no_matching_loaded_offer";
            message = "Inspected loaded merchants do not currently offer the requested item.";
        } else {
            code = "merchant_interaction_unavailable";
            message = "No loaded unprotected merchant could be opened and verified for trading.";
        }
        return failFinal(code, message, FailureType.NO_MATERIAL);
    }

    private TaskState failFinal(String code, String message, FailureType type) {
        if (failureMessage == null) {
            failureCode = code;
            failureMessage = message;
            failureType = type == null ? FailureType.UNKNOWN : type;
        }
        if ((openedMenu || openRequested) && player.containerMenu != player.inventoryMenu) {
            openedMenu = true;
            phase = Phase.CLEANUP;
        } else {
            openedMenu = false;
            phase = Phase.COMPLETE;
        }
        return TaskState.RUNNING;
    }

    private void note(String code) {
        observations.merge(code, 1, Integer::sum);
    }

    // 当前只检查菜单类型，没有绑定最初打开的那个菜单对象或编号；换成另一个商人菜单也会通过。
    private MerchantMenu merchantMenu() {
        return player.containerMenu instanceof MerchantMenu menu ? menu : null;
    }

    private int outputCount() {
        return PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(r.itemId));
    }

    private String childId(String label) {
        return r.getToolCallId() + "-trade-" + label + "-" + (++childSerial);
    }

    private long childDeadline(long ticks) {
        long lease = player.level().getGameTime() + ticks;
        r.extendDeadlineTo(lease);
        return lease;
    }

    private static int ceilDiv(int numerator, int denominator) {
        if (numerator <= 0) return 0;
        return (numerator + denominator - 1) / denominator;
    }

    @Override
    // 取消等结束场景会停子任务并尝试关界面；没有追回已经完成的交易，也没有把消费过的货币当作仍在背包。
    protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild = null;
            activeRecord = null;
            activePurpose = null;
        }
        if ((openedMenu || openRequested) && player.containerMenu != player.inventoryMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(context, 40, "semantic trade task ended");
            } catch (RuntimeException ignored) {
                outcomeUncertain = true;
            }
        }
        super.cleanup();
    }

    @Override
    // 报告最终背包数、已完成笔数、最后选择的支付要求与观察到的失败原因，另列结果是否仍不确定。
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        int observed = outputCount();
        data.put("goal", "final_main_inventory_count");
        data.put("item_id", r.itemId.toString());
        data.put("required_final_count", r.count);
        data.put("initial_count", initialOutputCount);
        data.put("observed_final_count", observed);
        data.put("goal_satisfied", observed >= r.count);
        data.put("merchant_kind_policy", r.merchantKind.name().toLowerCase());
        data.put("loaded_safe_merchant_count", merchants.size());
        data.put("protected_loaded_merchant_count", protectedMerchantCount);
        data.put("inspected_merchant_menus", inspectedMenus);
        data.put("completed_trades", completedTrades);
        data.put("outcome_uncertain", outcomeUncertain);
        if (selectedMerchantKind != null) data.put("selected_merchant_kind", selectedMerchantKind);
        if (selectedOutputPerTrade > 0) {
            data.put("selected_output_count_per_trade", selectedOutputPerTrade);
        }
        if (!selectedPaymentFacts.isEmpty()) {
            data.put("selected_payment", selectedPaymentFacts);
        }
        if (!observations.isEmpty()) {
            data.put("observed_offer_outcomes", Map.copyOf(observations));
        }
        if (!observedPaymentItemCandidates.isEmpty()) {
            data.put("observed_payment_item_candidates", observedPaymentItemCandidates.stream()
                    .map(ResourceLocation::toString).sorted().toList());
        }
        if (failureCode != null) {
            data.put("decision", Map.of(
                    "required", true,
                    "reason_code", failureCode,
                    "recovery_options", recoveryOptions(failureCode)));
        }
        return data;
    }

    private static List<String> recoveryOptions(String code) {
        return switch (code) {
            case "insufficient_payment", "payment_inventory_changed" -> List.of(
                    "acquire one of the observed payment items, then retry",
                    "allow another payment item or merchant family",
                    "use a different acquisition source",
                    "cancel");
            case "payment_policy_rejected" -> List.of(
                    "retry with an explicit allowed_payment_items policy chosen from the observed candidates",
                    "use another acquisition source",
                    "cancel");
            case "inventory_space_required" -> List.of(
                    "free main-inventory space, then retry",
                    "lower the requested final count",
                    "cancel");
            case "unknown_protected_label", "only_protected_loaded_merchants" -> List.of(
                    "resolve or revise protected labels explicitly",
                    "travel to a clearly unprotected trading area",
                    "use another acquisition source",
                    "cancel");
            case "ambiguous_offer_selection" -> List.of(
                    "use another merchant whose payment tuple is unambiguous",
                    "perform this trade manually",
                    "use another acquisition source",
                    "cancel");
            default -> List.of(
                    "travel until another eligible merchant is loaded, then retry",
                    "change merchant or payment policy",
                    "use another acquisition source",
                    "cancel");
        };
    }

    @Override
    protected String successMessage() {
        return "traded until the real main inventory held at least " + r.count
                + " of " + r.itemId;
    }

    @Override
    protected String timeoutMessage() {
        return "trading stopped making verifiable first-person progress; the real main inventory holds "
                + outputCount()
                + " of required final " + r.count;
    }

    @Override
    protected String cancelledMessage() {
        return "trading interrupted; the real main inventory holds " + outputCount()
                + " of required final " + r.count;
    }
}
