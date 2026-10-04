package org.maiwithu.maicraft.core.task.collect;

import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.ProgressBudget;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import org.maiwithu.maicraft.core.task.base.PickupNavigationRetry;
import org.maiwithu.maicraft.core.task.base.TargetSet;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.BuiltInRegistries;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.scan.DroppedItemObservation;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 反复寻找匹配的地面物品并走近，让服务器按正常拾取规则把物品放进背包。
 * 分为“找下一堆”和“走到这一堆”两步；物品消失还不够，必须看到对应背包增加才记为拾取成功。
 */
public final class CollectItemsCompanionTask extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH }

    private static final double WALK_SPEED = 1.0;
    /** 掉落实体消失或角色接触后，等待数据包同步的有界窗口。 */
    private static final int PICKUP_SYNC_TICKS = 20;
    private final ProgressBudget pickupProgress;
    private TerrainBill pickupTerrain = new TerrainBill();

    private Phase phase = Phase.SCAN;
    private NativePickupReceipt pickup;
    private UUID pickupUuid;
    private String pickupItemId;
    private int pickupAccountedUnits;
    private long pickupCursor;
    private long selectedReceiptWait = Long.MIN_VALUE;
    /** 所选物品堆的已确认收取和现场快照分开保留；失效引用不能因扫描为空而被当作已完成。 */
    private final Set<UUID> completedTargets = new HashSet<>();
    private final Map<UUID, JsonObject> targetObservations = new LinkedHashMap<>();
    private final Map<String, Integer> collectedItems = new LinkedHashMap<>();
    private int contactTicks;
    private int unreachable;
    private int disappearedWithoutReceipt;
    private int pickupRejected;
    private String lastUncollectedDetail;
    private final PickupNavigationRetry navigationRetry = new PickupNavigationRetry();
    /** 记录附近每个实体 ID 首次观察到的数量，用于核实原版实体 ID 间的堆叠合并。 */
    private final Map<Integer, Integer> firstObservedEntityCounts = new HashMap<>();

    /** 已到达但无法收入背包的物品实体 ID，供 SCAN 跳过，避免循环追踪。 */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);

    public CollectItemsCompanionTask(LocalPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
        pickupProgress = record.progressBudget(20L * 20);
    }

    @Override
    protected void onStart() {
        // 每次新任务清空计数和跳过名单；暂停恢复不会自动重新执行这个初始化。
        this.phase = Phase.SCAN;
        this.pickup = null;
        this.pickupUuid = null;
        this.pickupItemId = null;
        this.pickupAccountedUnits = 0;
        this.pickupCursor = ItemEntityReceipts.cursor(player);
        this.selectedReceiptWait = Long.MIN_VALUE;
        this.completedTargets.clear();
        this.targetObservations.clear();
        this.collectedItems.clear();
        this.contactTicks = 0;
        this.unreachable = 0;
        this.disappearedWithoutReceipt = 0;
        this.pickupRejected = 0;
        this.lastUncollectedDetail = null;
        this.firstObservedEntityCounts.clear();
        this.skipped.reset();
        this.pickupTerrain = new TerrainBill();
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        // 引用属于原观察维度；跨维度后先停止移动，交付未收取事实供模型重新选择。
        if (r.targetDimension != null && !r.targetDimension.equals(player.level().dimension().location())) {
            fail("the selected drop is in another dimension", FailureType.TARGET_LOST);
            return TaskState.FAILED;
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
                                + "without matching pickup and inventory confirmation"
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
            if (!completedTargets.containsAll(r.targetUuids)) {
                // 开始前就消失、离开范围或类型已变化的指定物品不能返回零件成功，也不能换成别处同类物品。
                fail("selected drop stack(s) are unavailable within the search radius or item filter; "
                        + "pickup remains unconfirmed", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            // 普通范围扫描没有剩余候选可正常结束；指定物品则须已逐堆确认收取。
            return TaskState.SUCCESS;
        }
        // 掉落所在格不能站立不代表无法拾取；由接触站位与正常导航尝试从周围靠近。
        pickup = NativePickupReceipt.begin(player, best);
        pickupUuid = best.getUUID();
        pickupItemId = BuiltInRegistries.ITEM.getKey(best.getItem().getItem()).toString();
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
        refreshTargetObservation(current);
        NativePickupReceipt.State receiptState = pickup.poll(player, PICKUP_SYNC_TICKS);
        // 逐刻保存本人已收取的部分，后续失联或换维度也不能抹掉先前已确认的效果。
        if (r.targetDimension != null) recordCollected(pickup.confirmedUnits(player));
        if (receiptState == NativePickupReceipt.State.RECEIVED) {
            // 模型点名的物品堆还须有服务器发给本人的同 UUID 拾取包；别人拿走后背包碰巧增量不能冒充完成。
            if (r.targetDimension != null
                    && ItemEntityReceipts.pickedUp(player, pickupUuid, pickupCursor) < pickup.expectedUnits()) {
                if (selectedReceiptWait == Long.MIN_VALUE) selectedReceiptWait = player.level().getGameTime();
                if (nav != null) nav.pause();
                else InputDriver.halt(player);
                if (player.level().getGameTime() - selectedReceiptWait < PICKUP_SYNC_TICKS) return TaskState.RUNNING;
                recordCollected(pickup.confirmedUnits(player));
                disappearedWithoutReceipt++;
                lastUncollectedDetail = "the selected drop has no matching native pickup confirmation for this player";
                finishTarget();
                return TaskState.RUNNING;
            }
            recordCollected(pickup.confirmedUnits(player));
            completedTargets.add(pickupUuid);
            finishTarget();
            return TaskState.RUNNING;
        }
        if (receiptState == NativePickupReceipt.State.AWAITING_INVENTORY_SYNC) {
            if (retargetProvenMerge()) return TaskState.RUNNING;
            if (nav != null) nav.pause();
            else InputDriver.halt(player);
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
            else InputDriver.halt(player);
            if (live.hasPickUpDelay()) {
                contactTicks = 0;
                return TaskState.RUNNING;
            }
            if (!NativePickupReceipt.canAccept(player, live.getItem())) {
                recordCollected(pickup.confirmedUnits(player));
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

        // 原路径无路后给下落、漂移留出短窗口；每刻仍先核对接触和入包，不要求模型另开补拾取。
        if (navigationRetry.waiting(live.getUUID(), player.level().getGameTime())) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
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
                if (navigationRetry.afterFailure(live.getUUID(), player.level().getGameTime(), nav.failType())) {
                    stopNav();
                    return TaskState.RUNNING;
                }
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
                ? null : CollectItemsApproach.goal(player, List.of(live), r.mayAlterTerrain);
    }

    private boolean pickupReceived() {
        return pickup != null && pickup.received(player);
    }

    private PlayerNav approachNavigation() {
        var next = PlayerNav.toRevalidating(player, this::targetGoal, WALK_SPEED,
                this::pickupReceived, r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM : PlayerNav.ContextProvider.DEFAULT);
        // 指定物品堆持续跟随原身份；开路许可交给同一地面导航，不再要求模型另开旅行与挖块任务。
        return r.targetUuids.isEmpty() ? next : next.walkingOnly();
    }

    private void nudge(ItemEntity target) {
        // 到达接触站位后只在有支撑的范围内微调；采收产物和普通拾取都不能直接冲进坑或保护格。
        var point = CollectItemsApproach.nudgePoint(player, target);
        if (!CollectItemsApproach.safeNudge(player, point)) {
            InputDriver.halt(player);
            // 目标刚滚离当前支撑时也沿用短暂重寻窗口，不能绕开路径重试直接要求模型补发任务。
            if (!navigationRetry.afterFailure(target.getUUID(), player.level().getGameTime(), FailureType.NO_PATH))
                fail("the final pickup approach is not safe on the observed footing", FailureType.NO_PATH);
            return;
        }
        InputDriver.stepToward(player, point, false);
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
        pickupItemId = null;
        pickupAccountedUnits = 0;
        selectedReceiptWait = Long.MIN_VALUE;
        contactTicks = 0;
        stopNav();
        phase = Phase.SCAN;
    }

    private String detailSuffix() {
        return lastUncollectedDetail == null || lastUncollectedDetail.isBlank()
                ? "" : "; last evidence: " + lastUncollectedDetail;
    }

    private ItemEntity nearestItem() {
        // 普通拾取按类型找最近物品；指定一堆时先筛 UUID，不能因另一堆同类物品更近就转移目标。
        AABB box = player.getBoundingBox().inflate(r.radius);
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved() || ie.getItem().isEmpty()) continue;
            if (!r.permits(ie.getUUID())) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            firstObservedEntityCounts.putIfAbsent(ie.getId(), ie.getItem().getCount());
            if (!r.targetUuids.isEmpty()) targetObservations.put(ie.getUUID(), DroppedItemObservation.describe(player, ie));
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
        // 背包满、超时或中断可能发生在整堆收完之前；报告已同步的本人收取，重复查询不重复累加。
        if (pickup != null && r.targetDimension != null) {
            refreshTargetObservation(pickup.liveEntity(player));
            recordCollected(pickup.confirmedUnits(player));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("collected_items", Map.copyOf(collectedItems));
        data.put("radius", r.radius);
        data.put("pickup_navigation", Map.of("may_alter_terrain", r.mayAlterTerrain, "confirmed_terrain_changes", pickupTerrain.snapshot()));
        data.put("unreachable_drop_stacks", unreachable);
        data.put("disappeared_without_inventory_receipt", disappearedWithoutReceipt);
        data.put("pickup_rejected_after_contact", pickupRejected);
        if (lastUncollectedDetail != null) data.put("last_uncollected_detail", lastUncollectedDetail);
        if (!r.targetUuids.isEmpty()) {
            // 回执逐一列出所选、已确认和未确认引用，保留位置及组件，让模型直接决定下一次走向。
            data.put("drop_collection", Map.of(
                    "requested_drop_refs", r.targetUuids.stream().map(this::reference).sorted().toList(),
                    "collected_drop_refs", r.targetUuids.stream().filter(completedTargets::contains).map(this::reference).sorted().toList(),
                    "unconfirmed_drop_refs", r.targetUuids.stream().filter(uuid -> !completedTargets.contains(uuid)).map(this::reference).sorted().toList(),
                    "observations", List.copyOf(targetObservations.values())));
        }
        return data;
    }

    @Override protected void stopNav() {
        // 每段导航结清后保存真实挖放记录，重寻、失败和取消都不丢失为拾取已经开出的通道。
        PlayerNav active = nav;
        super.stopNav();
        if (active != null) pickupTerrain.addAll(active.ledger());
    }

    private String reference(UUID uuid) {
        return (r.targetDimension == null ? player.level().dimension().location() : r.targetDimension) + "|" + uuid;
    }

    private void refreshTargetObservation(ItemEntity current) {
        // 收尾查询也保留最后看到的位置与余量；原实体被替换时沿用旧快照，不把另一堆混进回执。
        if (current == null || !current.getUUID().equals(pickupUuid) || !pickup.sameStackKind(current)) return;
        var observed = targetObservations.get(current.getUUID());
        if (observed != null) DroppedItemObservation.refresh(observed, player, current);
    }

    private void recordCollected(int count) {
        // 只把已有原生拾取流程确认的数量记到对应物品，部分入包也在失败回执中保留。
        if (r.targetDimension != null) count = Math.min(count, ItemEntityReceipts.pickedUp(player, pickupUuid, pickupCursor));
        int additional = Math.max(0, count - pickupAccountedUnits);
        pickupAccountedUnits += additional;
        r.addCollected(additional);
        if (additional > 0) {
            collectedItems.merge(pickupItemId, additional, Integer::sum);
            // 连续拾取只按已确认入包数量补时，物品消失和重复回执不能把剩余预算刷满。
            pickupProgress.observeCounter(player.level().getGameTime(), r.getCollected());
        }
    }

    /** 面板行动行的一句话汇报；说法来自拾取阶段，正在追的物品名是现场已确认的实体。 */
    @Override
    public String describeCurrentAction() {
        return switch (phase) {
            case SCAN -> "正在寻找地上的物品";
            case APPROACH -> pickupItemId == null ? "正在走近待拾取物品"
                    : "正在拾取物品 (" + r.getCollected() + " 件已入包)";
        };
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
