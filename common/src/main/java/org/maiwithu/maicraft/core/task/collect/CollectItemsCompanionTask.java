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
import java.util.UUID;

/**
 * 反复寻找匹配的地面物品并走近，让服务器按正常拾取规则把物品放进背包。
 * 分为“找下一堆”和“走到这一堆”两步；物品消失还不够，必须看到对应背包增加才记为拾取成功。
 */
public final class CollectItemsCompanionTask extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH }

    private static final double WALK_SPEED = 1.0;
    /** 掉落实体消失或角色接触后，等待数据包同步的有界窗口。 */
    private static final int PICKUP_SYNC_TICKS = 20;

    private Phase phase = Phase.SCAN;
    private NativePickupReceipt pickup;
    private UUID pickupUuid;
    private int contactTicks;
    private int unreachable;
    private int disappearedWithoutReceipt;
    private int pickupRejected;
    private String lastUncollectedDetail;
    /** 记录附近每个实体 ID 首次观察到的数量，用于核实原版实体 ID 间的堆叠合并。 */
    private final Map<Integer, Integer> firstObservedEntityCounts = new HashMap<>();

    /** 已到达但无法收入背包的物品实体 ID，供 SCAN 跳过，避免循环追踪。 */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);

    public CollectItemsCompanionTask(LocalPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        // 每次新任务清空计数和跳过名单；暂停恢复不会自动重新执行这个初始化。
        this.phase = Phase.SCAN;
        this.pickup = null;
        this.pickupUuid = null;
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
        // 没有候选后，再区分是真收完了，还是有走不到、消失但未收到、接触后无法拾取等未完成情况。
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
            // 半径范围内没有剩余物品；即使数量为零，也可用回执确认“此处没有物品”。
            return TaskState.SUCCESS;
        }
        if (!r.targetUuids.isEmpty() && !NativePickupReceipt.insideVanillaTouchEnvelope(player, best)
                && !CollectItemsApproach.safeTarget(player, best.blockPosition())) {
            fail("the scoped drop has no observed dry or shallow-water footing", FailureType.NO_PATH); return TaskState.FAILED;
        }
        pickup = NativePickupReceipt.begin(player, best);
        pickupUuid = best.getUUID();
        contactTicks = 0;
        nav = approachNavigation();
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState tickApproach() {
        // 先看上一堆是否已进入背包，再决定继续靠近还是等待同步，不一消失就当作自己拿到了。
        if (pickup == null) {
            finishTarget();
            return TaskState.RUNNING;
        }

        ItemEntity current = pickup.liveEntity(player);
        // 数字实体ID可能被重用；持续靠近前仍认原UUID和完整物品样式，不把另一堆接成已授权产物。
        if (!r.targetUuids.isEmpty() && current != null
                && (!current.getUUID().equals(pickupUuid) || !r.permits(current.getUUID()) || !pickup.sameStackKind(current))) {
            fail("the tracked drop identity changed before pickup", FailureType.TARGET_LOST);
            return TaskState.FAILED;
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
            // 已碰到物品就停下移动，等拾取冷却结束；背包没有空间时明确失败，不继续顶着物品走。
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
        if (!r.targetUuids.isEmpty() && !CollectItemsApproach.safeTarget(player, live.blockPosition())) {
            fail("the scoped drop left observed dry or shallow-water footing", FailureType.NO_PATH); return TaskState.FAILED;
        }
        contactTicks = 0;

        // 站在同一格也可能还没碰到小小的掉落物，最后一点距离用普通前进补齐，不能只凭格子相同算成功。
        if (player.blockPosition().equals(live.blockPosition())) {
            stopNav();
            nudge(live);
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = approachNavigation();
        }

        switch (nav.tick()) {
            case RUNNING -> { /* walking to it */ }
            case ARRIVED -> {
                stopNav();
                nudge(live);
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
        return live == null || !r.targetUuids.isEmpty() && (!live.getUUID().equals(pickupUuid) || !r.permits(live.getUUID()))
                ? null : GoalCompiler.standOn(live.blockPosition());
    }

    private boolean pickupReceived() {
        return pickup != null && pickup.received(player);
    }

    private PlayerNav approachNavigation() {
        var next = PlayerNav.toRevalidating(player, this::targetGoal, WALK_SPEED,
                this::pickupReceived, PlayerNav.ContextProvider.DEFAULT);
        // 内部受限收取只复用现有步行与浅水通行，不为捡一堆已确认物品自动尝试其他交通。
        return r.targetUuids.isEmpty() ? next : next.walkingOnly();
    }

    private void nudge(ItemEntity target) {
        // 到了同一格仍未接触时保留原生短靠近，但受限产物不能绕过保护格或跨入未知深水。
        if (!r.targetUuids.isEmpty() && !CollectItemsApproach.safeNudge(player, target.position())) {
            InputDriver.halt(player); fail("the final pickup approach is not safe on the observed footing", FailureType.NO_PATH); return;
        }
        InputDriver.stepToward(player, target.position(), false);
    }

    /** 两堆物品合并时，旧实体消失不等于被捡走；若已观察到的另一堆数量增加足够，就改为追踪合并后的那堆。 */
    private boolean retargetProvenMerge() {
        if (pickup == null) return false;
        AABB box = player.getBoundingBox().inflate(r.radius);
        ItemEntity survivor = player.level().getEntitiesOfClass(ItemEntity.class, box,
                        entity -> !entity.isRemoved() && r.permits(entity.getUUID()) && pickup.sameStackKind(entity))
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
        pickupUuid = survivor.getUUID();
        contactTicks = 0;
        stopNav();
        return true;
    }

    private void finishTarget() {
        pickup = null;
        pickupUuid = null;
        contactTicks = 0;
        stopNav();
        phase = Phase.SCAN;
    }

    private String detailSuffix() {
        return lastUncollectedDetail == null || lastUncollectedDetail.isBlank()
                ? "" : "; last evidence: " + lastUncollectedDetail;
    }

    private ItemEntity nearestItem() {
        // 普通拾取按类型找最近物品；内部已限定身份时先筛UUID，不能因另一堆更近就转移收取目标。
        AABB box = player.getBoundingBox().inflate(r.radius);
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()) continue;
            if (!r.permits(ie.getUUID())) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            firstObservedEntityCounts.putIfAbsent(ie.getId(), ie.getItem().getCount());
            candidates.add(ie);
        }
        return skipped.pick(candidates, Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    @Override
    protected void cleanup() {
        // 结束后停止走路；没有直接改背包或删除地面实体，拾取本身由游戏完成。
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
