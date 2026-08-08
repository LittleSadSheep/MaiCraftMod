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
