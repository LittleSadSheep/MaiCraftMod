// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.combat;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Causally bounded collection of drops created by targets defeated in one combat beat.
 *
 * <p>The client is not told a mob-drop parent id. The strongest evidence it can actually prove
 * is therefore a conjunction: the item id did not exist in the pre-kill snapshot, it first
 * appeared inside the short death synchronization window, and it appeared close to the recorded
 * corpse position. {@link ItemEntity#getOwner()} cannot strengthen that proof because the
 * thrower/pickup-target fields are not synchronized to this client. A merely nearby old stack is
 * never collected. If a new drop merges into such a stack, the causal portion cannot be separated
 * from somebody else's items, so the merge is reported as ambiguous and left alone.</p>
 */
final class LootSweep {

    enum PickupContact { NONE, WAITING, NO_CAPACITY, REFUSED_WITH_CAPACITY }
    enum VanishState { CLEAR, WAITING_FOR_INVENTORY_SYNC, UNCONFIRMED }

    /** Allow death and item-spawn packets to settle before deciding that no loot exists. */
    private static final int DROP_SETTLE_TICKS = 10;
    /** Vanilla mob drops spawn at the corpse and drift only a short distance in this window. */
    private static final double ATTRIBUTION_RADIUS = 4.5;
    private static final double ATTRIBUTION_RADIUS_SQR =
            ATTRIBUTION_RADIUS * ATTRIBUTION_RADIUS;
    /** Repeated proven no-path results make a drop unreachable, not silently collected. */
    private static final int MAX_APPROACH_FAILURES = 2;
    /** Require repeated synchronized capacity evidence before diagnosing a full inventory. */
    private static final int NO_CAPACITY_CONFIRM_TICKS = 3;
    /** True vanilla contact plus free capacity should settle promptly; beyond this it is not full. */
    private static final int CAPABLE_CONTACT_SETTLE_TICKS = 20;
    /** Entity removal can precede the matching inventory packet by several client ticks. */
    private static final int VANISH_RECONCILE_TICKS = 10;

    private final LocalPlayer player;
    private final Map<Integer, Integer> preexistingCounts = new HashMap<>();
    private final Map<Integer, Item> preexistingItems = new HashMap<>();
    private final Map<Integer, Vec3> preexistingPositions = new HashMap<>();
    private final Set<Integer> observedPreexistingDisappearances = new HashSet<>();
    private final Map<Item, Integer> vanishedPreexistingByItem = new HashMap<>();
    /** Unmatched portion of vanished old stacks, consumed once when a surviving new id grows. */
    private final Map<Item, Integer> unmatchedVanishedPreexistingByItem = new HashMap<>();
    private final Map<Integer, Integer> trackedCounts = new HashMap<>();
    private final Set<Integer> tracked = new LinkedHashSet<>();
    private final Set<Integer> skipped = new HashSet<>();
    private final List<BlockPos> deathPositions = new ArrayList<>();
    private final Map<Item, Integer> inventoryAtSweepStart = new HashMap<>();
    private final Map<Item, Integer> sweepAttributed = new HashMap<>();
    private final Map<Item, Integer> sweepAmbiguous = new HashMap<>();
    private final Map<Item, Integer> sweepUnreachable = new HashMap<>();
    private final Map<Item, Integer> attributedTotal = new HashMap<>();
    private final Map<Item, Integer> collectedFromSettledSweeps = new HashMap<>();
    private final Map<Item, Integer> unreachableByItem = new HashMap<>();
    private final Map<Item, Integer> ambiguousByItem = new HashMap<>();
    private final Map<Item, Integer> rejectedLocalPlayerDrops = new HashMap<>();

    private long settleUntil;
    private int approachFailures;
    private int capableContactTicks;
    private int noCapacityContactTicks;
    private int contactItemId = -1;
    private long unaccountedSince = Long.MIN_VALUE;
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
            preexistingItems.put(item.getId(), item.getItem().getItem());
            preexistingPositions.put(item.getId(), item.position());
        }
    }

    /** Begin a new post-kill synchronization window. */
    void begin(BlockPos where) {
        Set<Integer> staleBaseline = new HashSet<>();
        for (int id : preexistingCounts.keySet()) {
            if (!(player.clientLevel.getEntity(id) instanceof ItemEntity)) staleBaseline.add(id);
        }
        preexistingCounts.keySet().removeAll(staleBaseline);
        preexistingItems.keySet().removeAll(staleBaseline);
        preexistingPositions.keySet().removeAll(staleBaseline);
        observedPreexistingDisappearances.clear();
        vanishedPreexistingByItem.clear();
        unmatchedVanishedPreexistingByItem.clear();
        active = true;
        deathPositions.clear();
        tracked.clear();
        trackedCounts.clear();
        skipped.clear();
        approachFailures = 0;
        capableContactTicks = 0;
        noCapacityContactTicks = 0;
        contactItemId = -1;
        inventoryAtSweepStart.clear();
        snapshotInventory(inventoryAtSweepStart);
        sweepAttributed.clear();
        sweepAmbiguous.clear();
        sweepUnreachable.clear();
        unaccountedSince = Long.MIN_VALUE;
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
     * Admit new item ids observed inside the death window.  In 1.21.1 neither ItemEntity.thrower
     * nor its pickup target is synchronized to the client, so {@code getOwner()==null} is not
     * evidence of a wild/mob drop and must not reject legitimate loot.  The usable client proof is
     * the pre-kill id/count snapshot plus the tight death time/space window. A simultaneous local
     * inventory decrease for the same item type is specific evidence that this body dropped it.
     */
    void discover() {
        if (!active) return;
        boolean admissionOpen = player.level().getGameTime() <= settleUntil;
        observePreexistingDisappearances();
        for (BlockPos death : deathPositions) {
            AABB box = new AABB(death).inflate(ATTRIBUTION_RADIUS);
            for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
                if (!nearAnyDeath(item)) continue;
                int id = item.getId();
                if (tracked.contains(id)) {
                    int trackedBefore = trackedCounts.getOrDefault(id, item.getItem().getCount());
                    int growth = item.getItem().getCount() - trackedBefore;
                    int oldMergedUnits = consumeVanishedPreexisting(
                            item.getItem().getItem(), growth);
                    if (growth > 0 && oldMergedUnits > 0) {
                        ambiguousMergedCount++;
                        skipped.add(id);
                        // The external growth is the old stack. What became uncollectable is the
                        // causal portion that was already on this tracked entity before the merge.
                        int causalPortion = Math.min(trackedBefore,
                                sweepAttributed.getOrDefault(item.getItem().getItem(), 0));
                        account(ambiguousByItem, item.getItem().getItem(), causalPortion);
                        account(sweepAmbiguous, item.getItem().getItem(), causalPortion);
                        Constants.LOG.warn("[maicraft-loot] leaving inseparable merged stack {} x{} "
                                        + "at {}; causal_units_at_risk={}",
                                itemName(item.getItem().getItem()), item.getItem().getCount(),
                                item.blockPosition().toShortString(), causalPortion);
                    }
                    trackedCounts.put(id, item.getItem().getCount());
                    continue;
                }
                Integer before = preexistingCounts.get(id);
                if (before == null) {
                    if (admissionOpen && !locallyDroppedDuringSweep(item)) {
                        Item type = item.getItem().getItem();
                        int current = item.getItem().getCount();
                        // Vanilla may keep the NEW id when an old nearby stack merges into it.
                        // The old id then vanishes before this branch sees the survivor. Consume at
                        // most current-1 old units: a genuinely new id in the death window must
                        // still contain at least one causal unit. The inseparable stack is left in
                        // place and reported rather than silently taking the old portion.
                        int oldMergedUnits = consumeVanishedPreexisting(
                                type, Math.max(0, current - 1));
                        if (oldMergedUnits > 0) {
                            int causalUnits = current - oldMergedUnits;
                            ambiguousMergedCount++;
                            account(attributedTotal, type, causalUnits);
                            account(sweepAttributed, type, causalUnits);
                            account(ambiguousByItem, type, causalUnits);
                            account(sweepAmbiguous, type, causalUnits);
                            preexistingCounts.put(id, current);
                            preexistingItems.put(id, type);
                            preexistingPositions.put(id, item.position());
                            Constants.LOG.warn("[maicraft-loot] leaving new-id survivor {} x{} "
                                            + "at {}; contains {} old unit(s), causal_units_at_risk={}",
                                    itemName(type), current,
                                    item.blockPosition().toShortString(),
                                    oldMergedUnits, causalUnits);
                        } else {
                            tracked.add(id);
                            trackedCounts.put(id, current);
                            account(attributedTotal, type, current);
                            account(sweepAttributed, type, current);
                            Constants.LOG.info("[maicraft-loot] attributed {} x{} at {} "
                                            + "(client_owner_visible={})",
                                    itemName(type), current,
                                    item.blockPosition().toShortString(), item.getOwner() != null);
                        }
                    } else if (admissionOpen) {
                        account(rejectedLocalPlayerDrops,
                                item.getItem().getItem(), item.getItem().getCount());
                        preexistingCounts.put(id, item.getItem().getCount());
                        preexistingItems.put(id, item.getItem().getItem());
                        preexistingPositions.put(id, item.position());
                        Constants.LOG.warn("[maicraft-loot] rejected local-player drop evidence "
                                        + "for {} x{} at {}",
                                itemName(item.getItem().getItem()), item.getItem().getCount(),
                                item.blockPosition().toShortString());
                    }
                } else if (admissionOpen && !tracked.contains(id)
                        && item.getItem().getCount() > before) {
                    ambiguousMergedCount++;
                    account(ambiguousByItem, item.getItem().getItem(),
                            item.getItem().getCount() - before);
                    account(sweepAmbiguous, item.getItem().getItem(),
                            item.getItem().getCount() - before);
                    preexistingCounts.put(id, item.getItem().getCount());
                }
            } else if (admissionOpen && item.getItem().getCount() > before) {
                // A causal drop may have merged into an old stack, but the units cannot be
                // separated. Never take the old stack; expose the uncertain causal units.
                int growth = item.getItem().getCount() - before;
                ambiguousMergedCount++;
                account(ambiguousByItem, item.getItem().getItem(), growth);
                account(sweepAmbiguous, item.getItem().getItem(), growth);
                preexistingCounts.put(id, item.getItem().getCount());
            }
        }
    }

    private void observeTrackedGrowth(ItemEntity item) {
        int id = item.getId();
        int trackedBefore = trackedCounts.getOrDefault(id, item.getItem().getCount());
        int growth = item.getItem().getCount() - trackedBefore;
        int oldMergedUnits = consumeVanishedPreexisting(item.getItem().getItem(), growth);
        if (growth > 0 && oldMergedUnits > 0) {
            ambiguousMergedCount++;
            skipped.add(id);
            int causalPortion = Math.min(trackedBefore,
                    sweepAttributed.getOrDefault(item.getItem().getItem(), 0));
            account(ambiguousByItem, item.getItem().getItem(), causalPortion);
            account(sweepAmbiguous, item.getItem().getItem(), causalPortion);
        }
        trackedCounts.put(id, item.getItem().getCount());
    }

    private void admit(ItemEntity item) {
        Item type = item.getItem().getItem();
        int current = item.getItem().getCount();
        int oldMergedUnits = consumeVanishedPreexisting(type, Math.max(0, current - 1));
        if (oldMergedUnits > 0) {
            int causalUnits = current - oldMergedUnits;
            ambiguousMergedCount++;
            account(attributedTotal, type, causalUnits);
            account(sweepAttributed, type, causalUnits);
            account(ambiguousByItem, type, causalUnits);
            account(sweepAmbiguous, type, causalUnits);
            rememberAsPreexisting(item);
            return;
        }
        tracked.add(item.getId());
        trackedCounts.put(item.getId(), current);
        account(attributedTotal, type, current);
        account(sweepAttributed, type, current);
        Constants.LOG.info("[maicraft-loot] causally attributed {} x{} at {} (age={}, velocity={})",
                itemName(type), current, item.blockPosition().toShortString(), item.tickCount,
                item.getDeltaMovement());
    }

    private void rememberAsPreexisting(ItemEntity item) {
        preexistingCounts.put(item.getId(), item.getItem().getCount());
        preexistingItems.put(item.getId(), item.getItem().getItem());
        preexistingPositions.put(item.getId(), item.position());
    }

    private record CausalMatch(boolean strong, boolean plausible, String reason) { }

    private CausalMatch causalMatch(ItemEntity item) {
        long now = player.level().getGameTime();
        long estimatedSpawn = now - Math.max(0, item.tickCount);
        DeathWitness best = null;
        double bestMargin = Double.NEGATIVE_INFINITY;
        boolean weaklyCompatible = false;
        for (DeathWitness death : deaths) {
            long temporalOffset = estimatedSpawn - death.observedAt();
            if (temporalOffset < -SPAWN_TICK_SKEW) continue;
            int age = Math.max(0, item.tickCount);
            Vec3 delta = item.position().subtract(Vec3.atCenterOf(death.position()));
            double horizontal = Math.hypot(delta.x, delta.z);
            double observedHorizontalSpeed = Math.hypot(
                    item.getDeltaMovement().x, item.getDeltaMovement().z);
            // Reverse the observed drag only far enough to establish a conservative physical
            // envelope. This expands with actual age and motion instead of using a corpse radius.
            double physicalReach = 1.0 + age * Math.max(0.12, observedHorizontalSpeed * 1.25);
            double verticalReach = 1.5 + age * (0.25 + Math.abs(item.getDeltaMovement().y));
            double margin = Math.min(physicalReach - horizontal,
                    verticalReach - Math.abs(delta.y));
            boolean weakMotion = horizontal <= physicalReach * 2.0
                    && Math.abs(delta.y) <= verticalReach * 2.0;
            weaklyCompatible |= weakMotion;
            // Target loot and the death update are produced by the same server event. A new item
            // whose first visible tick is much later is nearby new loot, but not provably this
            // target's loot; keep it unresolved instead of silently annexing it.
            if (temporalOffset > SPAWN_TICK_SKEW) continue;
            if (margin > bestMargin) {
                bestMargin = margin;
                best = death;
            }
        }
        if (best == null) {
            return new CausalMatch(false, weaklyCompatible,
                    weaklyCompatible
                            ? "spawn became visible after the target death packet boundary"
                            : "spawn age or motion is incompatible with every observed death");
        }
        if (bestMargin < 0.0) {
            return new CausalMatch(false, weaklyCompatible,
                    "new during death event but motion cannot prove origin at a death position");
        }
        return new CausalMatch(true, true,
                "new entity id, death-consistent spawn age and physically compatible trajectory");
    }

    private void observePreexistingDisappearances() {
        for (var entry : preexistingItems.entrySet()) {
            int id = entry.getKey();
            if (observedPreexistingDisappearances.contains(id)) continue;
            Vec3 baselinePosition = preexistingPositions.get(id);
            if (baselinePosition == null || !nearAnyDeath(baselinePosition)) continue;
            Entity entity = player.clientLevel.getEntity(id);
            if (!(entity instanceof ItemEntity) || entity.isRemoved()) {
                observedPreexistingDisappearances.add(id);
                account(vanishedPreexistingByItem, entry.getValue(),
                        preexistingCounts.getOrDefault(id, 0));
                account(unmatchedVanishedPreexistingByItem, entry.getValue(),
                        preexistingCounts.getOrDefault(id, 0));
            }
        }
    }

    private int consumeVanishedPreexisting(Item item, int maximum) {
        if (maximum <= 0) return 0;
        int available = unmatchedVanishedPreexistingByItem.getOrDefault(item, 0);
        int consumed = Math.min(available, maximum);
        if (consumed <= 0) return 0;
        int remaining = available - consumed;
        if (remaining == 0) unmatchedVanishedPreexistingByItem.remove(item);
        else unmatchedVanishedPreexistingByItem.put(item, remaining);
        return consumed;
    }

    private boolean locallyDroppedDuringSweep(ItemEntity item) {
        Item type = item.getItem().getItem();
        return inventoryCount(type) < inventoryAtSweepStart.getOrDefault(type, 0);
    }

    private boolean nearAnyDeath(Vec3 position) {
        long now = player.level().getGameTime();
        for (DeathWitness death : deaths) {
            long elapsed = Math.max(0L, now - death.observedAt());
            double reach = 1.0 + elapsed * 0.12;
            if (position.distanceToSqr(
                    death.position().getX() + 0.5, death.position().getY() + 0.5,
                    death.position().getZ() + 0.5) <= reach * reach) {
                return true;
            }
        }
        return false;
    }

    List<ItemEntity> live() {
        List<ItemEntity> out = new ArrayList<>();
        for (int id : tracked) {
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved() && !skipped.contains(id)
                    && approachMayBeRetried(item)) {
                out.add(item);
            }
        }
        return out;
    }

    void prune() {
        tracked.removeIf(id -> {
            Entity entity = player.clientLevel.getEntity(id);
            boolean gone = !(entity instanceof ItemEntity) || entity.isRemoved();
            if (gone) {
                trackedCounts.remove(id);
                blockedApproaches.remove(id);
            }
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
     * Mirror the server's exact Player.aiStep touch query: horizontal expansion 1.0, vertical
     * expansion 0.5.  The previous all-axis {@code inflate(1)} stopped a full block too early on
     * uneven ground and then misdiagnosed the perfectly free inventory as full.
     */
    PickupContact pickupContact() {
        ItemEntity item = nearest().orElse(null);
        if (item == null || !insideServerTouchQuery(item)) {
            resetContactEvidence();
            return PickupContact.NONE;
        }
        if (contactItemId != item.getId()) {
            resetContactEvidence();
            contactItemId = item.getId();
        }
        if (item.hasPickUpDelay()) return PickupContact.WAITING;

        if (!inventoryCanAccept(item.getItem())) {
            capableContactTicks = 0;
            return ++noCapacityContactTicks >= NO_CAPACITY_CONFIRM_TICKS
                    ? PickupContact.NO_CAPACITY : PickupContact.WAITING;
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
        nearest().ifPresent(item -> {
            long fingerprint = localWorldFingerprint(item.blockPosition());
            if (blockedApproaches.put(item.getId(), new BlockedApproach(
                    player.blockPosition().immutable(), item.blockPosition().immutable(),
                    fingerprint, item.getItem().getItem(), item.getItem().getCount())) == null) {
                account(unreachableByItem,
                        item.getItem().getItem(), item.getItem().getCount());
                account(sweepUnreachable,
                        item.getItem().getItem(), item.getItem().getCount());
            }
        });
    }

    private boolean approachMayBeRetried(ItemEntity item) {
        BlockedApproach blocked = blockedApproaches.get(item.getId());
        if (blocked == null) return true;
        if (!blocked.playerPosition().equals(player.blockPosition())
                || !blocked.itemPosition().equals(item.blockPosition())
                || blocked.localWorldFingerprint() != localWorldFingerprint(item.blockPosition())) {
            blockedApproaches.remove(item.getId());
            subtract(sweepUnreachable, blocked.item(), blocked.count());
            subtract(unreachableByItem, blocked.item(), blocked.count());
            return true;
        }
        return false;
    }

    private long localWorldFingerprint(BlockPos center) {
        long hash = 0xcbf29ce484222325L;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1),
                center.offset(1, 2, 1))) {
            hash ^= player.level().getBlockState(pos).hashCode();
            hash *= 0x100000001b3L;
        }
        return hash;
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
        deaths.clear();
        resetContactEvidence();
        Constants.LOG.info("[maicraft-loot] completed causal sweep: {}", receipt);
    }

    int unreachableCount() {
        return blockedApproaches.size();
    }

    int ambiguousMergedCount() {
        return ambiguousMergedCount;
    }

    boolean mustSettle() {
        return active && (settling()
                || !live().isEmpty()
                || !unaccountedByItem().isEmpty()
                || hasUnreachableCurrentSweep()
                || hasAmbiguousCurrentSweep()
                || hasUnresolvedCurrentSweep());
    }

    boolean hasUnreachableCurrentSweep() {
        return !sweepUnreachable.isEmpty();
    }

    boolean hasAmbiguousCurrentSweep() {
        return !sweepAmbiguous.isEmpty();
    }

    boolean hasUnresolvedCurrentSweep() {
        return !unresolvedCandidates.isEmpty();
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

    String unresolvedEvidence() {
        return unresolvedCandidates.values().stream()
                .map(candidate -> itemName(candidate.item()) + " x" + candidate.count()
                        + " at " + candidate.position().toShortString()
                        + " (" + candidate.reason() + ")")
                .sorted()
                .toList().toString();
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
        Map<Item, Integer> blocked = new HashMap<>();
        for (int id : tracked) {
            if (skipped.contains(id)) continue;
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved()) {
                if (blockedApproaches.containsKey(id)) {
                    account(blocked, item.getItem().getItem(), item.getItem().getCount());
                } else {
                    account(remaining, item.getItem().getItem(), item.getItem().getCount());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attributed_by_item", named(attributedTotal));
        out.put("confirmed_inventory_gain_by_item", named(confirmed));
        out.put("remaining_reachable_by_item", named(remaining));
        out.put("remaining_loaded_but_unreached_by_item", named(blocked));
        out.put("unreachable_by_item", named(unreachableByItem));
        out.put("ambiguous_merged_by_item", named(ambiguousByItem));
        out.put("rejected_local_player_drops_by_item", named(rejectedLocalPlayerDrops));
        out.put("preexisting_stack_disappeared_by_item", named(vanishedPreexistingByItem));
        out.put("unconfirmed_vanished_by_item", named(unaccountedByItem()));
        out.put("unresolved_candidate_evidence", unresolvedEvidence());
        out.put("death_site_count", deaths.size());
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
            if (skipped.contains(id) || blockedApproaches.containsKey(id)) continue;
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved()) {
                account(out, item.getItem().getItem(), item.getItem().getCount());
            }
        }
        return out;
    }

    private Map<Item, Integer> allTrackedLiveCounts() {
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
        Map<Item, Integer> liveCounts = allTrackedLiveCounts();
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

    private static void subtract(Map<Item, Integer> counts, Item item, int amount) {
        int remaining = counts.getOrDefault(item, 0) - amount;
        if (remaining > 0) counts.put(item, remaining);
        else counts.remove(item);
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
