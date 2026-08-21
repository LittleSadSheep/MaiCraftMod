package org.maiwithu.maicraft.core.task.collect;

import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import org.maiwithu.maicraft.core.task.base.TargetSet;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent-level item sweeper for {@link CollectItemsTaskRecord}: "pick up the
 * dropped items around here." The entity already auto-absorbs items within ~1
 * block ({@code setCanPickUpLoot}); this goal actively walks it to each
 * scattered drop with the pathfinder so nothing is left behind after a mine or a
 * attack.
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN     → nearest matching ItemEntity within the radius; none → DONE.
 *   APPROACH → Navigator into its exact cell; only entity disappearance plus a
 *              synchronized inventory delta confirms pickup, then re-SCAN.
 * </pre>
 */
public final class CollectItemsCompanionTask extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH }

    private static final double WALK_SPEED = 1.0;
    /** Bounded packet-settle window after the entity disappears or contact is made. */
    private static final int PICKUP_SYNC_TICKS = 20;

    private Phase phase = Phase.SCAN;
    private NativePickupReceipt pickup;
    private int contactTicks;
    private int unreachable;
    private int disappearedWithoutReceipt;
    private int pickupRejected;
    private String lastUncollectedDetail;
    /** First observed count for every nearby id, used to prove an id-to-id vanilla merge. */
    private final Map<Integer, Integer> firstObservedEntityCounts = new HashMap<>();

    /** Item-entity ids we reached but couldn't absorb, so SCAN won't loop on them. */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);

    public CollectItemsCompanionTask(LocalPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        this.phase = Phase.SCAN;
        this.pickup = null;
        this.contactTicks = 0;
        this.unreachable = 0;
        this.disappearedWithoutReceipt = 0;
        this.pickupRejected = 0;
        this.lastUncollectedDetail = null;
        this.firstObservedEntityCounts.clear();
        this.skipped.reset();
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        return switch (phase) {
            case SCAN -> tickScan();
            case APPROACH -> tickApproach();
        };
    }

    private TaskState tickScan() {
        ItemEntity best = nearestItem();
        if (best == null) {
            if (unreachable > 0) {
                fail("collected " + r.getCollected() + " " + r.label + ", but "
                                + unreachable + " loaded drop stack(s) had a proven navigation "
                                + "failure" + detailSuffix(),
                        FailureType.NO_PATH);
                return TaskState.FAILED;
            }
            if (disappearedWithoutReceipt > 0) {
                fail("collected " + r.getCollected() + " " + r.label + ", but "
                                + disappearedWithoutReceipt + " tracked drop stack(s) disappeared "
                                + "without a synchronized matching inventory increase"
                                + detailSuffix(),
                        FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            if (pickupRejected > 0) {
                fail("collected " + r.getCollected() + " " + r.label + ", but vanilla "
                                + "pickup did not accept " + pickupRejected
                                + " contacted drop stack(s)" + detailSuffix(),
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            // Nothing left within radius. Success at zero remains a valid "nothing here" receipt.
            return TaskState.SUCCESS;
        }
        pickup = NativePickupReceipt.begin(player, best);
        contactTicks = 0;
        nav = PlayerNav.toRevalidating(player, this::targetGoal, WALK_SPEED,
                this::pickupReceived, PlayerNav.ContextProvider.DEFAULT);
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState tickApproach() {
        if (pickup == null) {
            finishTarget();
            return TaskState.RUNNING;
        }

        NativePickupReceipt.State receiptState = pickup.poll(player, PICKUP_SYNC_TICKS);
        if (receiptState == NativePickupReceipt.State.RECEIVED) {
            r.addCollected(pickup.confirmedUnits(player));
            finishTarget();
            return TaskState.RUNNING;
        }
        if (receiptState == NativePickupReceipt.State.AWAITING_INVENTORY_SYNC) {
            if (retargetProvenMerge()) return TaskState.RUNNING;
            if (nav != null) nav.pause();
            return TaskState.RUNNING;
        }
        if (receiptState == NativePickupReceipt.State.DISAPPEARED_WITHOUT_RECEIPT) {
            disappearedWithoutReceipt++;
            lastUncollectedDetail = "the exact tracked entity vanished before its inventory receipt";
            finishTarget();
            return TaskState.RUNNING;
        }

        ItemEntity live = pickup.liveEntity(player);
        if (live == null) return TaskState.RUNNING;
        if (NativePickupReceipt.insideVanillaTouchEnvelope(player, live)) {
            if (nav != null) nav.pause();
            if (live.hasPickUpDelay()) {
                contactTicks = 0;
                return TaskState.RUNNING;
            }
            if (!NativePickupReceipt.canAccept(player, live.getItem())) {
                r.addCollected(pickup.confirmedUnits(player));
                fail("reached and contacted a loaded " + r.label + " drop, but no main-inventory "
                                + "slot can accept its remaining stack; collected "
                                + r.getCollected() + " before the inventory filled",
                        FailureType.NO_SPACE);
                return TaskState.FAILED;
            }
            if (++contactTicks >= PICKUP_SYNC_TICKS) {
                pickupRejected++;
                lastUncollectedDetail = "the body stayed inside vanilla's touch envelope with "
                        + "pickup delay cleared and inventory capacity available";
                finishTarget();
            }
            return TaskState.RUNNING;
        }
        contactTicks = 0;

        // A* owns integer feet cells. Once both bodies share the same cell, finish the
        // remaining fraction with ordinary first-person forward input until the exact vanilla
        // touch envelope overlaps. Cell membership by itself is never a pickup or a failure.
        if (player.blockPosition().equals(live.blockPosition())) {
            stopNav();
            InputDriver.stepToward(player, live.position(), false);
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.toRevalidating(player, this::targetGoal, WALK_SPEED,
                    this::pickupReceived, PlayerNav.ContextProvider.DEFAULT);
        }

        switch (nav.tick()) {
            case RUNNING -> { /* walking to it */ }
            case ARRIVED -> {
                stopNav();
                InputDriver.stepToward(player, live.position(), false);
            }
            case FAILED -> {
                skipped.skip(live);
                unreachable++;
                lastUncollectedDetail = nav.failReason();
                finishTarget();
            }
        }
        return TaskState.RUNNING;
    }

    private GoalCompiler.Compiled targetGoal() {
        ItemEntity live = pickup == null ? null : pickup.liveEntity(player);
        return live == null ? null : GoalCompiler.standOn(live.blockPosition());
    }

    private boolean pickupReceived() {
        return pickup != null && pickup.received(player);
    }

    /**
     * Item entities try to merge while moving. If the selected id is the loser, disappearance is
     * not a failed pickup: a previously observed compatible survivor whose count grew by the full
     * missing stack is concrete merge evidence. Transfer the receipt to that survivor; its full
     * post-merge stack must then enter the inventory before collection is credited.
     */
    private boolean retargetProvenMerge() {
        if (pickup == null) return false;
        AABB box = player.getBoundingBox().inflate(r.radius);
        ItemEntity survivor = player.level().getEntitiesOfClass(ItemEntity.class, box,
                        entity -> !entity.isRemoved() && pickup.sameStackKind(entity))
                .stream()
                .filter(entity -> {
                    Integer before = firstObservedEntityCounts.get(entity.getId());
                    return before != null
                            && entity.getItem().getCount() - before >= pickup.expectedUnits();
                })
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (survivor == null) return false;
        firstObservedEntityCounts.put(survivor.getId(), survivor.getItem().getCount());
        pickup = NativePickupReceipt.begin(player, survivor);
        contactTicks = 0;
        stopNav();
        return true;
    }

    private void finishTarget() {
        pickup = null;
        contactTicks = 0;
        stopNav();
        phase = Phase.SCAN;
    }

    private String detailSuffix() {
        return lastUncollectedDetail == null || lastUncollectedDetail.isBlank()
                ? "" : "; last evidence: " + lastUncollectedDetail;
    }

    private ItemEntity nearestItem() {
        AABB box = player.getBoundingBox().inflate(r.radius);
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            firstObservedEntityCounts.putIfAbsent(ie.getId(), ie.getItem().getCount());
            candidates.add(ie);
        }
        return skipped.pick(candidates, Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    @Override
    protected void cleanup() {
        InputDriver.halt(player);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("radius", r.radius);
        data.put("unreachable_drop_stacks", unreachable);
        data.put("disappeared_without_inventory_receipt", disappearedWithoutReceipt);
        data.put("pickup_rejected_after_contact", pickupRejected);
        if (lastUncollectedDetail != null) data.put("last_uncollected_detail", lastUncollectedDetail);
        return data;
    }

    @Override
    protected String successMessage() {
        return "collected " + r.getCollected() + " " + r.label;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after collecting " + r.getCollected() + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after collecting " + r.getCollected() + " " + r.label;
    }
}
