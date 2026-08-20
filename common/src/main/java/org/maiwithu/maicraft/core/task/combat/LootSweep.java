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
        return touching;
    }

    boolean pickupBlocked() {
        return pickupWaitTicks >= PICKUP_SETTLE_TICKS;
    }

    private boolean insideNativePickupEnvelope(ItemEntity item) {
        return player.getBoundingBox().inflate(1.0).intersects(item.getBoundingBox());
    }

    void noteApproachFailure() {
        if (++approachFailures < MAX_APPROACH_FAILURES) return;
        approachFailures = 0;
        nearest().ifPresent(item -> {
            if (skipped.add(item.getId())) unreachableCount++;
        });
    }

    private java.util.Optional<ItemEntity> nearest() {
        return live().stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    void finish() {
        // Keep the baseline: an unreachable/ambiguous stack remains pre-existing for later kills.
        tracked.clear();
        trackedCounts.clear();
        skipped.clear();
        deathPositions.clear();
        active = false;
        pickupWaitTicks = 0;
    }

    int unreachableCount() {
        return unreachableCount;
    }

    int ambiguousMergedCount() {
        return ambiguousMergedCount;
    }

    boolean mustSettle() {
        return active && (settling() || !live().isEmpty());
    }
}
