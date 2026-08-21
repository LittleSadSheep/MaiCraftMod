package org.maiwithu.maicraft.core.task.combat;

import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Causally bounded collection of drops created by targets defeated in one combat beat.
 *
 * <p>The client is not told a mob-drop parent id. The strongest evidence it can actually prove
 * is therefore a conjunction: the item id did not exist before the hit, it has no thrower/owner,
 * it first appeared inside the short death synchronization window, and it appeared close to the
 * recorded corpse position. A merely nearby old stack is never collected. If a new drop merges
 * into such a stack, the causal portion cannot be separated from somebody else's items, so the
 * merge is reported as ambiguous and left alone.</p>
 */
final class LootSweep {

    /** Allow death and item-spawn packets to settle before deciding that no loot exists. */
    private static final int DROP_SETTLE_TICKS = 10;
    /** Vanilla mob drops spawn at the corpse and drift only a short distance in this window. */
    private static final double ATTRIBUTION_RADIUS = 4.5;
    private static final double ATTRIBUTION_RADIUS_SQR =
            ATTRIBUTION_RADIUS * ATTRIBUTION_RADIUS;
    /** Repeated proven no-path results make a drop unreachable, not silently collected. */
    private static final int MAX_APPROACH_FAILURES = 2;
    /** At collision range, absence of pickup this long is authoritative evidence of no capacity. */
    private static final int PICKUP_SETTLE_TICKS = 40;

    private final LocalPlayer player;
    private final Map<Integer, Integer> preexistingCounts = new HashMap<>();
    private final Map<Integer, Integer> trackedCounts = new HashMap<>();
    private final Set<Integer> tracked = new LinkedHashSet<>();
    private final Set<Integer> skipped = new HashSet<>();
    private final List<BlockPos> deathPositions = new ArrayList<>();

    private long settleUntil;
    private int approachFailures;
    private int pickupWaitTicks;
    private int unreachableCount;
    private int ambiguousMergedCount;
    private boolean active;

    LootSweep(LocalPlayer player) {
        this.player = player;
    }

    /** Snapshot everything already at the prospective kill site before a lethal hit lands. */
    void rememberPreexisting(BlockPos around) {
        AABB box = new AABB(around).inflate(ATTRIBUTION_RADIUS);
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            preexistingCounts.put(item.getId(), item.getItem().getCount());
        }
    }

    /** Begin a new post-kill synchronization window. */
    void begin(BlockPos where) {
        preexistingCounts.keySet().removeIf(id ->
                !(player.clientLevel.getEntity(id) instanceof ItemEntity));
        active = true;
        deathPositions.clear();
        tracked.clear();
        trackedCounts.clear();
        skipped.clear();
        approachFailures = 0;
        pickupWaitTicks = 0;
        addDeath(where);
    }

    /** Sweeping/ranged damage can settle more than one target in the same tick. */
    void addDeath(BlockPos where) {
        if (!deathPositions.contains(where)) deathPositions.add(where.immutable());
        settleUntil = Math.max(settleUntil,
                player.level().getGameTime() + DROP_SETTLE_TICKS);
    }

    boolean settling() {
        return player.level().getGameTime() <= settleUntil;
    }

    /**
     * Admit only new, ownerless item entities observed inside the death window. Owner-bearing
     * entities are thrown/assigned items, not ordinary mob loot. Count growth on an old id is an
     * inseparable merge and deliberately remains untouched.
     */
    void discover() {
        if (!active) return;
        boolean admissionOpen = player.level().getGameTime() <= settleUntil;
        for (BlockPos death : deathPositions) {
            AABB box = new AABB(death).inflate(ATTRIBUTION_RADIUS);
            for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
                if (!nearAnyDeath(item)) continue;
                int id = item.getId();
                if (tracked.contains(id)) {
                    int trackedBefore = trackedCounts.getOrDefault(id, item.getItem().getCount());
                    if (!admissionOpen && item.getItem().getCount() > trackedBefore) {
                        ambiguousMergedCount++;
                        skipped.add(id);
                    }
                    trackedCounts.put(id, item.getItem().getCount());
                    continue;
                }
                Integer before = preexistingCounts.get(id);
                if (before == null) {
                    if (admissionOpen && item.getOwner() == null) {
                        tracked.add(id);
                        trackedCounts.put(id, item.getItem().getCount());
                    } else if (item.getOwner() != null) {
                        preexistingCounts.put(id, item.getItem().getCount());
                    }
                } else if (!tracked.contains(id) && item.getItem().getCount() > before) {
                    ambiguousMergedCount++;
                    preexistingCounts.put(id, item.getItem().getCount());
                }
            }
        }
    }

    private boolean nearAnyDeath(ItemEntity item) {
        for (BlockPos death : deathPositions) {
            if (item.position().distanceToSqr(
                    death.getX() + 0.5, death.getY() + 0.5, death.getZ() + 0.5)
                    <= ATTRIBUTION_RADIUS_SQR) {
                return true;
            }
        }
        return false;
    }

    List<ItemEntity> live() {
        List<ItemEntity> out = new ArrayList<>();
        for (int id : tracked) {
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved() && !skipped.contains(id)) {
                out.add(item);
            }
        }
        return out;
    }

    void prune() {
        tracked.removeIf(id -> {
            Entity entity = player.clientLevel.getEntity(id);
            boolean gone = !(entity instanceof ItemEntity) || entity.isRemoved();
            if (gone) trackedCounts.remove(id);
            return gone;
        });
    }

    /** Aim for the exact live item cells; item motion is revalidated by trackGoal. */
    NavGoal goal() {
        List<NavGoal> goals = live().stream()
                .map(item -> NavGoal.exact(item.blockPosition()))
                .toList();
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    /**
     * Once vanilla pickup envelopes overlap, stop walking and let server inventory sync settle.
     * Returning true does not mean success: the item must actually disappear from the tracked set.
     */
    boolean waitingInsidePickupEnvelope() {
        boolean touching = nearest().map(this::insideNativePickupEnvelope).orElse(false);
        if (touching) {
            pickupWaitTicks++;
        } else {
            pickupWaitTicks = 0;
        }
        noCapacityContactTicks = 0;
        return ++capableContactTicks >= CAPABLE_CONTACT_SETTLE_TICKS
                ? PickupContact.REFUSED_WITH_CAPACITY : PickupContact.WAITING;
    }

    private boolean insideServerTouchQuery(ItemEntity item) {
        return NativePickupReceipt.insideVanillaTouchEnvelope(player, item);
    }

    private boolean inventoryCanAccept(ItemStack stack) {
        return NativePickupReceipt.canAccept(player, stack);
    }

    private void resetContactEvidence() {
        capableContactTicks = 0;
        noCapacityContactTicks = 0;
        contactItemId = -1;
    }

    String contactEvidence() {
        ItemEntity item = nearest().orElse(null);
        if (item == null) return "no live attributable item";
        return itemName(item.getItem().getItem()) + " x" + item.getItem().getCount()
                + " at " + item.blockPosition().toShortString()
                + "; client_capacity=" + inventoryCanAccept(item.getItem())
                + "; pickup_delay_visible=" + item.hasPickUpDelay()
                + "; true_touch=" + insideServerTouchQuery(item)
                + "; capable_contact_ticks=" + capableContactTicks
                + "; no_capacity_contact_ticks=" + noCapacityContactTicks;
    }

    boolean needsFineApproach() {
        ItemEntity item = nearest().orElse(null);
        return item != null && !insideServerTouchQuery(item)
                && player.blockPosition().equals(item.blockPosition());
    }

    net.minecraft.world.phys.Vec3 nearestPosition() {
        return nearest().map(ItemEntity::position).orElse(player.position());
    }

    /**
     * Do not treat a removed ItemEntity as collected until item conservation balances against
     * synchronized inventory gain or another still-live attributed stack (the normal merge case).
     */
    VanishState vanishState() {
        Map<Item, Integer> unaccounted = unaccountedByItem();
        if (unaccounted.isEmpty()) {
            unaccountedSince = Long.MIN_VALUE;
            return VanishState.CLEAR;
        }
        long now = player.level().getGameTime();
        if (unaccountedSince == Long.MIN_VALUE) unaccountedSince = now;
        return now - unaccountedSince >= VANISH_RECONCILE_TICKS
                ? VanishState.UNCONFIRMED : VanishState.WAITING_FOR_INVENTORY_SYNC;
    }

    String vanishEvidence() {
        return "unconfirmed=" + named(unaccountedByItem())
                + "; inventory_gain=" + named(currentSweepInventoryGain())
                + "; live_attributed=" + named(reachableTrackedLiveCounts())
                + "; ambiguous_merge=" + named(sweepAmbiguous);
    }

    void noteApproachFailure() {
        if (++approachFailures < MAX_APPROACH_FAILURES) return;
        approachFailures = 0;
        nearest().ifPresent(item -> {
            if (skipped.add(item.getId())) {
                unreachableCount++;
                account(unreachableByItem,
                        item.getItem().getItem(), item.getItem().getCount());
                account(sweepUnreachable,
                        item.getItem().getItem(), item.getItem().getCount());
            }
        });
    }

    private java.util.Optional<ItemEntity> nearest() {
        return live().stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    void finish() {
        // Keep the baseline: an unreachable/ambiguous stack remains pre-existing for later kills.
        settleCurrentSweepCollection();
        active = false;
        Map<String, Object> receipt = report();
        tracked.clear();
        trackedCounts.clear();
        skipped.clear();
        deathPositions.clear();
        resetContactEvidence();
        Constants.LOG.info("[maicraft-loot] completed causal sweep: {}", receipt);
    }

    int unreachableCount() {
        return unreachableCount;
    }

    int ambiguousMergedCount() {
        return ambiguousMergedCount;
    }

    boolean mustSettle() {
        return active && (settling()
                || !live().isEmpty()
                || !unaccountedByItem().isEmpty()
                || hasUnreachableCurrentSweep()
                || hasAmbiguousCurrentSweep());
    }

    boolean hasUnreachableCurrentSweep() {
        return !sweepUnreachable.isEmpty();
    }

    boolean hasAmbiguousCurrentSweep() {
        return !sweepAmbiguous.isEmpty();
    }

    String unreachableEvidence() {
        return "unreachable=" + named(sweepUnreachable)
                + "; confirmed_inventory_gain=" + named(currentSweepInventoryGain())
                + "; remaining_reachable=" + named(reachableTrackedLiveCounts());
    }

    String ambiguousEvidence() {
        return "inseparable_causal_units=" + named(sweepAmbiguous)
                + "; preexisting_stack_disappeared=" + named(vanishedPreexistingByItem)
                + "; confirmed_inventory_gain=" + named(currentSweepInventoryGain());
    }

    Map<String, Object> report() {
        Map<Item, Integer> confirmed = new HashMap<>(collectedFromSettledSweeps);
        if (active) {
            for (var entry : sweepAttributed.entrySet()) {
                int gain = Math.max(0, inventoryCount(entry.getKey())
                        - inventoryAtSweepStart.getOrDefault(entry.getKey(), 0));
                account(confirmed, entry.getKey(), Math.min(entry.getValue(), gain));
            }
        }
        Map<Item, Integer> remaining = new HashMap<>();
        for (int id : tracked) {
            if (skipped.contains(id)) continue;
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved()) {
                account(remaining, item.getItem().getItem(), item.getItem().getCount());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attributed_by_item", named(attributedTotal));
        out.put("confirmed_inventory_gain_by_item", named(confirmed));
        out.put("remaining_reachable_by_item", named(remaining));
        out.put("unreachable_by_item", named(unreachableByItem));
        out.put("ambiguous_merged_by_item", named(ambiguousByItem));
        out.put("rejected_local_player_drops_by_item", named(rejectedLocalPlayerDrops));
        out.put("preexisting_stack_disappeared_by_item", named(vanishedPreexistingByItem));
        out.put("unconfirmed_vanished_by_item", named(unaccountedByItem()));
        out.put("death_site_count", deathPositions.size());
        out.put("active_sweep", active);
        out.put("attribution_window_open", active && settling());
        out.put("contact_evidence", contactEvidence());
        return out;
    }

    private void settleCurrentSweepCollection() {
        for (var entry : sweepAttributed.entrySet()) {
            int gain = Math.max(0, inventoryCount(entry.getKey())
                    - inventoryAtSweepStart.getOrDefault(entry.getKey(), 0));
            account(collectedFromSettledSweeps,
                    entry.getKey(), Math.min(entry.getValue(), gain));
        }
    }

    private Map<Item, Integer> currentSweepInventoryGain() {
        Map<Item, Integer> out = new HashMap<>();
        for (Item item : sweepAttributed.keySet()) {
            int gain = Math.max(0,
                    inventoryCount(item) - inventoryAtSweepStart.getOrDefault(item, 0));
            if (gain > 0) out.put(item, gain);
        }
        return out;
    }

    private Map<Item, Integer> reachableTrackedLiveCounts() {
        Map<Item, Integer> out = new HashMap<>();
        for (int id : tracked) {
            if (skipped.contains(id)) continue;
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved()) {
                account(out, item.getItem().getItem(), item.getItem().getCount());
            }
        }
        return out;
    }

    private Map<Item, Integer> unaccountedByItem() {
        Map<Item, Integer> gains = currentSweepInventoryGain();
        Map<Item, Integer> liveCounts = reachableTrackedLiveCounts();
        Map<Item, Integer> out = new HashMap<>();
        for (var entry : sweepAttributed.entrySet()) {
            int missing = entry.getValue()
                    - gains.getOrDefault(entry.getKey(), 0)
                    - liveCounts.getOrDefault(entry.getKey(), 0)
                    - sweepAmbiguous.getOrDefault(entry.getKey(), 0)
                    - sweepUnreachable.getOrDefault(entry.getKey(), 0);
            if (missing > 0) out.put(entry.getKey(), missing);
        }
        return out;
    }

    private void snapshotInventory(Map<Item, Integer> out) {
        out.clear();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) account(out, stack.getItem(), stack.getCount());
        }
    }

    private int inventoryCount(Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void account(Map<Item, Integer> counts, Item item, int amount) {
        if (amount > 0) counts.merge(item, amount, Integer::sum);
    }

    private static Map<String, Integer> named(Map<Item, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> itemName(entry.getKey())))
                .forEach(entry -> out.put(itemName(entry.getKey()), entry.getValue()));
        return out;
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
