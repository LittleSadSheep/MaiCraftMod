// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.combat;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
import java.util.Optional;

/**
 * 围绕本轮死亡地点追踪可能的战利品，再结合背包变化判断哪些收到了、哪些还在地上、哪些解释不清。
 * 客户端没有掉落物所属怪物的直接字段，所以这里使用出现时间和移动距离等线索推断，不能保证来源绝对正确。
 * 旧物、混堆和来源推断不足只保留为观察信息；死亡现场的候选物品仍尝试原生拾取，入包与击杀来源分别记账。
 */
final class LootSweep {

    enum PickupContact { NONE, WAITING, NO_CAPACITY, REFUSED_WITH_CAPACITY }
    enum VanishState { CLEAR, WAITING_FOR_INVENTORY_SYNC, UNCONFIRMED }

    /** 数据包顺序可能使新掉落物比死亡更新早一个客户端 tick 出现。 */
    private static final int SPAWN_TICK_SKEW = 1;
    /** 必须多次观察到同步后的容量证据，才判定背包已满。 */
    private static final int NO_CAPACITY_CONFIRM_TICKS = 3;
    /** 已进入原版拾取接触范围且背包有空位时，应很快完成拾取；超时后不能再归因于背包已满。 */
    private static final int CAPABLE_CONTACT_SETTLE_TICKS = 20;
    /** 掉落实体消失可能比对应背包数据包早数个客户端 tick。 */
    private static final int VANISH_RECONCILE_TICKS = 10;

    private final LocalPlayer player;
    // 分别记住原来就在地上的物品、本轮候选物品和已经确认的背包增加量，不能把它们当成同一份数量。
    private final Map<Integer, Integer> preexistingCounts = new HashMap<>();
    private final Map<Integer, Item> preexistingItems = new HashMap<>();
    private final Map<Integer, Vec3> preexistingPositions = new HashMap<>();
    private final Set<Integer> observedPreexistingDisappearances = new HashSet<>();
    private final Map<Item, Integer> vanishedPreexistingByItem = new HashMap<>();
    /** 旧实体消失后尚未匹配的物品数量；后续新实体堆叠增长时只消费一次。 */
    private final Map<Item, Integer> unmatchedVanishedPreexistingByItem = new HashMap<>();
    private final Map<Integer, Integer> trackedCounts = new HashMap<>();
    private final Set<Integer> tracked = new LinkedHashSet<>();
    private final List<DeathWitness> deaths = new ArrayList<>();
    private final Map<Item, Integer> inventoryAtSweepStart = new HashMap<>();
    private final Map<Item, Integer> sweepAttributed = new HashMap<>();
    /** 本轮实际追踪的完整堆量，包含旧物与来源未知物品；只用于核实拾取，不冒充击杀产量。 */
    private final Map<Item, Integer> sweepPickupExpected = new HashMap<>();
    /** 已观察到实体减少且同步入包的数量；后续合堆只沿用此前确认值，避免把本刻实体消失重复算作拾取。 */
    private final Map<Item, Integer> sweepReceivedUnits = new HashMap<>();
    private final Map<Item, Integer> observedPickupTotal = new HashMap<>();
    private final Map<Item, Integer> sweepAmbiguous = new HashMap<>();
    private final Map<Item, Integer> sweepUnreachable = new HashMap<>();
    private final Map<Integer, BlockedApproach> blockedApproaches = new HashMap<>();
    private final Map<Integer, UnresolvedCandidate> unresolvedCandidates = new HashMap<>();
    private final Map<Item, Integer> attributedTotal = new HashMap<>();
    private final Map<Item, Integer> collectedFromSettledSweeps = new HashMap<>();
    private final Map<Item, Integer> unreachableByItem = new HashMap<>();
    private final Map<Item, Integer> ambiguousByItem = new HashMap<>();
    private final Map<Item, Integer> observedLocalPlayerDrops = new HashMap<>();

    private int capableContactTicks;
    private int noCapacityContactTicks;
    private int contactItemId = -1;
    private long unaccountedSince = Long.MIN_VALUE;
    private int ambiguousMergedCount;
    private boolean active;

    private static final class DeathWitness {
        private final int sourceEntityId;
        private final BlockPos position;
        private final long observedAt;
        private long lifecycleEndedAt = Long.MIN_VALUE;

        private DeathWitness(int sourceEntityId, BlockPos position, long observedAt) {
            this.sourceEntityId = sourceEntityId;
            this.position = position;
            this.observedAt = observedAt;
        }

        int sourceEntityId() { return sourceEntityId; }
        BlockPos position() { return position; }
        long observedAt() { return observedAt; }
    }

    private record BlockedApproach(BlockPos playerPosition, BlockPos itemPosition,
                                   long localWorldFingerprint, Item item, int count) { }

    private record UnresolvedCandidate(Item item, int count, BlockPos position, String reason) { }

    LootSweep(LocalPlayer player) {
        this.player = player;
    }

    /**
     * 致命一击前记录客户端已知的所有物品。若只按任意尸体半径建立快照，旧物品移动穿过半径时会被误判为新生成。
     */
    // 记下当前客户端能渲染的所有地上物品，不只死亡地点附近；后来看到同一编号就知道它不是刚出现。
    void rememberPreexisting() {
        for (Entity entity : player.clientLevel.entitiesForRendering()) {
            if (entity instanceof ItemEntity item && !item.isRemoved()) {
                preexistingCounts.put(item.getId(), item.getItem().getCount());
                preexistingItems.put(item.getId(), item.getItem().getItem());
                preexistingPositions.put(item.getId(), item.position());
            }
        }
    }

    /** 击杀后开启新的同步观察窗口。 */
    // 开始一轮死亡掉落收集：清掉上一轮的临时等待和候选，保留整个战斗的累计统计，并记下此刻背包。
    void begin(int sourceEntityId, BlockPos where) {
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
        deaths.clear();
        tracked.clear();
        trackedCounts.clear();
        blockedApproaches.clear();
        unresolvedCandidates.clear();
        capableContactTicks = 0;
        noCapacityContactTicks = 0;
        contactItemId = -1;
        inventoryAtSweepStart.clear();
        snapshotInventory(inventoryAtSweepStart);
        sweepAttributed.clear();
        sweepPickupExpected.clear();
        sweepReceivedUnits.clear();
        sweepAmbiguous.clear();
        sweepUnreachable.clear();
        unaccountedSince = Long.MIN_VALUE;
        addDeath(sourceEntityId, where);
    }

    /** 横扫或远程伤害可能在同一 tick 内结算多个目标。 */
    void addDeath(int sourceEntityId, BlockPos where) {
        DeathWitness witness = new DeathWitness(sourceEntityId, where.immutable(),
                player.level().getGameTime());
        if (deaths.stream().noneMatch(old -> old.sourceEntityId() == sourceEntityId)) {
            deaths.add(witness);
        }
    }

    // 目标的死亡动画还没结束，或刚结束不到额外一刻时，继续允许新掉落进入观察。
    // 这里按客户端看见的生命周期等待，不是服务器明确宣布掉落物已经全部发送。
    boolean settling() {
        long now = player.level().getGameTime();
        for (DeathWitness death : deaths) {
            Entity source = player.clientLevel.getEntity(death.sourceEntityId());
            // LivingEntity 的死亡动画或移除状态是服务器确认的生命周期终点。若首次观察时实体已经移除，再等一个世界 tick 即可接收该更新的全部数据包，
            // 无需人为延长十刻的掉落归属窗口。
            boolean lifecycleActive = source != null && !source.isRemoved()
                    && (!(source instanceof LivingEntity living)
                    || !living.isDeadOrDying() || living.deathTime < 20);
            if (lifecycleActive) return true;
            if (death.lifecycleEndedAt == Long.MIN_VALUE) death.lifecycleEndedAt = now;
            // 来源实体结束生命周期后，完整观察一个客户端世界更新周期。这只是数据包边界，不是掉落归属超时；仍由生成时间和轨迹判断哪些实体由本次击杀造成。
            if (now <= death.lifecycleEndedAt + 1) return true;
        }
        return false;
    }

    /**
     * 接纳被击败实体的同步生命周期尚未结束时观察到的新物品 ID。在 1.21.1 中，ItemEntity.thrower 和拾取目标不会同步给客户端，
     * 因此 {@code getOwner()==null} 不能证明物品来自野外或其他生物，也不能据此拒绝正常战利品。客户端可用证据是击杀前 ID/数量快照、生成时间和运动轨迹；
     * 同类型物品同时从角色背包减少也只作为可能由角色丢出的线索，不据此证明归属或拒绝拾取。
     */
    // 死亡现场先接纳可拾取候选，再单独记录来源线索；不因归属不明、旧堆或角色自己丢出的可能性拒绝靠近。
    void discover() {
        if (!active) return;
        boolean admissionOpen = settling();
        observePreexistingDisappearances();
        for (Entity entity : player.clientLevel.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved()) continue;
            int id = item.getId();
            if (tracked.contains(id)) {
                observeTrackedGrowth(item);
                continue;
            }
            Integer before = preexistingCounts.get(id);
            if (before == null) {
                if (!admissionOpen) continue;
                CausalMatch match = causalMatch(item);
                if (locallyDroppedDuringSweep(item) && (match.plausible() || nearAnyDeath(item.position()))) {
                    account(observedLocalPlayerDrops,
                            item.getItem().getItem(), item.getItem().getCount());
                    rememberAsPreexisting(item);
                    trackForPickup(item);
                } else if (match.strong()) {
                    admit(item);
                } else if (match.plausible() || nearAnyDeath(item.position())) {
                    unresolvedCandidates.put(id, new UnresolvedCandidate(
                            item.getItem().getItem(), item.getItem().getCount(),
                            item.blockPosition().immutable(), match.reason()));
                    rememberAsPreexisting(item);
                    trackForPickup(item);
                }
            } else if (admissionOpen && nearAnyDeath(item.position())) {
                // 现场已有物品也可收取；数量增长只说明可能混堆，不要求角色先把旧物与新产物分开。
                if (item.getItem().getCount() > before) {
                    int growth = item.getItem().getCount() - before;
                    ambiguousMergedCount++;
                    account(ambiguousByItem, item.getItem().getItem(), growth);
                    account(sweepAmbiguous, item.getItem().getItem(), growth);
                }
                trackForPickup(item);
            }
        }
        refreshPickupReceipts();
    }

    // 本轮堆叠增长时记录可能混入的旧物，仍继续收取；其他已追踪堆的合并不重复增加待入包数量。
    private void observeTrackedGrowth(ItemEntity item) {
        int id = item.getId();
        int trackedBefore = trackedCounts.getOrDefault(id, item.getItem().getCount());
        int growth = item.getItem().getCount() - trackedBefore;
        int oldMergedUnits = consumeVanishedPreexisting(item.getItem().getItem(), growth);
        if (growth > 0 && oldMergedUnits > 0) {
            ambiguousMergedCount++;
            int causalPortion = Math.min(trackedBefore,
                    sweepAttributed.getOrDefault(item.getItem().getItem(), 0));
            account(ambiguousByItem, item.getItem().getItem(), causalPortion);
            account(sweepAmbiguous, item.getItem().getItem(), causalPortion);
        }
        if (growth > 0) {
            Item type = item.getItem().getItem();
            int expected = sweepPickupExpected.getOrDefault(type, 0);
            int extra = Math.min(growth, Math.max(0, allTrackedLiveCounts().getOrDefault(type, 0)
                    + sweepReceivedUnits.getOrDefault(type, 0) - expected));
            account(sweepPickupExpected, type, extra);
            account(observedPickupTotal, type, extra);
        }
        trackedCounts.put(id, item.getItem().getCount());
    }

    // 来源推定只记疑似击杀产物，拾取则接纳完整堆叠；混入的旧物不再造成收取拒绝。
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
            trackForPickup(item);
            return;
        }
        trackForPickup(item);
        account(attributedTotal, type, current);
        account(sweepAttributed, type, current);
        Constants.LOG.info("[maicraft-loot] causally attributed {} x{} at {} (age={}, velocity={})",
                itemName(type), current, item.blockPosition().toShortString(), item.tickCount,
                item.getDeltaMovement());
    }

    /** 先固定现场实体，再核实整堆入包；同类已追踪实体消失后合入新实体时，沿用原有待收数量。 */
    private void trackForPickup(ItemEntity item) {
        if (tracked.contains(item.getId())) return;
        Item type = item.getItem().getItem();
        int pending = Math.max(0, sweepPickupExpected.getOrDefault(type, 0)
                - sweepReceivedUnits.getOrDefault(type, 0)
                - allTrackedLiveCounts().getOrDefault(type, 0));
        int extra = Math.max(0, item.getItem().getCount() - pending);
        tracked.add(item.getId());
        trackedCounts.put(item.getId(), item.getItem().getCount());
        account(sweepPickupExpected, type, extra);
        account(observedPickupTotal, type, extra);
    }

    /** 观察完整现场后再匹配同步背包：合堆仍在地上的数量不能被别处库存增长冒充本轮已拾取。 */
    private void refreshPickupReceipts() {
        Map<Item, Integer> live = allTrackedLiveCounts();
        Map<Item, Integer> gains = currentSweepInventoryGain();
        for (var entry : sweepPickupExpected.entrySet()) {
            int missing = Math.max(0, entry.getValue() - live.getOrDefault(entry.getKey(), 0));
            int received = Math.min(missing, gains.getOrDefault(entry.getKey(), 0));
            sweepReceivedUnits.merge(entry.getKey(), received, Math::max);
        }
    }

    private void rememberAsPreexisting(ItemEntity item) {
        preexistingCounts.put(item.getId(), item.getItem().getCount());
        preexistingItems.put(item.getId(), item.getItem().getItem());
        preexistingPositions.put(item.getId(), item.position());
    }

    private record CausalMatch(boolean strong, boolean plausible, String reason) { }

    // 用当前时间减物品的客户端存活刻数估计出现时间，并按速度估算它能从死亡点移动多远。
    // 时间相差一刻以内且位置符合估算才算较强线索；这仍是估计，客户端没有“来自哪只怪”的直接编号。
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
            // 只反推已观察到的运动距离，以建立保守的物理边界；该边界随实际年龄和位移扩展，不使用固定尸体半径。
            double physicalReach = 1.0 + age * Math.max(0.12, observedHorizontalSpeed * 1.25);
            double verticalReach = 1.5 + age * (0.25 + Math.abs(item.getDeltaMovement().y));
            double margin = Math.min(physicalReach - horizontal,
                    verticalReach - Math.abs(delta.y));
            boolean weakMotion = horizontal <= physicalReach * 2.0
                    && Math.abs(delta.y) <= verticalReach * 2.0;
            weaklyCompatible |= weakMotion;
            // 目标掉落和死亡更新由同一服务器事件产生。首次可见时间晚很多的新物品虽是附近新掉落，却无法证明属于此目标；保留未归属状态，不要静默收编。
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

    // 记录死亡附近原来存在、现在不见的物品数量，供后面估计合堆。
    // 这里没有直接证明消失原因；物品也可能被其他玩家捡走或卸载。
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

    // 把尚未分配的旧物品消失数量按物品种类扣给当前合堆猜测，同一份数量不重复使用。
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

    // 背包里这种物品比本轮开始时少，就把新看到的同类物品当作可能由玩家丢出；没有读取真实丢弃者。
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

    // 返回本轮仍可见且允许尝试靠近的候选，来源推断和混堆记录不影响继续收取。
    List<ItemEntity> live() {
        List<ItemEntity> out = new ArrayList<>();
        for (int id : tracked) {
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved()
                    && approachMayBeRetried(item)) {
                out.add(item);
            }
        }
        return out;
    }

    // 清掉已经看不到的实体编号和局部记录；是否真的进了背包，还要用 vanishState 另查。
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

    /** 以仍存在的物品格为目标；物品移动会由 trackGoal 重新核实。 */
    NavGoal goal() {
        List<NavGoal> goals = live().stream()
                .map(item -> NavGoal.exact(item.blockPosition()))
                .toList();
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    /**
     * 与服务器的 Player.aiStep 接触检测保持一致：水平扩展 1.0 格，垂直扩展 0.5 格。
     * 之前各轴统一 {@code inflate(1)} 会在不平地面提前一整格停止，进而把明明有空间的背包误报为已满。
     */
    // 靠近到原版拾取范围后，先等拾取延迟；连续三次确认无容量才报背包满。
    // 有容量却连续二十次仍未入包，则报告拾取未发生。
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
        if (item == null) return "no live pickup candidate";
        return itemName(item.getItem().getItem()) + " x" + item.getItem().getCount()
                + " at " + item.blockPosition().toShortString()
                + "; client_capacity=" + inventoryCanAccept(item.getItem())
                + "; pickup_delay_visible=" + item.hasPickUpDelay()
                + "; true_touch=" + insideServerTouchQuery(item)
                + "; capable_contact_ticks=" + capableContactTicks
                + "; no_capacity_contact_ticks=" + noCapacityContactTicks;
    }

    // 与物品在同一个方块格里，不代表身体已经碰到它；这种情况需要再往实际物品位置挪一点。
    boolean needsFineApproach() {
        ItemEntity item = nearest().orElse(null);
        return item != null && !insideServerTouchQuery(item)
                && player.blockPosition().equals(item.blockPosition());
    }

    Vec3 nearestPosition() {
        return nearest().map(ItemEntity::position).orElse(player.position());
    }

    /**
     * 只有物品守恒关系能由已同步的背包增加量或另一仍存活的归属堆叠（正常合并情况）解释时，才把已移除的 ItemEntity 视为已拾取。
     */
    // 物品没了但背包还没对上数量时，再等十刻同步；仍对不上就报告未确认，不能直接说捡到了。
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
                + "; live_pickup_candidates=" + named(reachableTrackedLiveCounts())
                + "; ambiguous_merge=" + named(sweepAmbiguous);
    }

    // 记下这次走不到的物品位置、玩家位置和物品附近方块形态，避免在条件完全没变时反复找同一条路。
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

    // 玩家移动、物品移动或附近地形变化时，撤销这次不可达记录，让寻路重新试；否则继续跳过。
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

    private Optional<ItemEntity> nearest() {
        return live().stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    // 结算本轮背包新增量并保留累计数，再关闭窗口、清掉追踪列表；不是把剩余实体自动收进背包。
    void finish() {
        // 保留旧物基线用于说明来源；是否继续收取由现场范围和原生结果决定。
        settleCurrentSweepCollection();
        active = false;
        Map<String, Object> receipt = report();
        tracked.clear();
        trackedCounts.clear();
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

    // 死亡同步、待收物品、真实入包差额和不可达结果需要结算；来源推断不足本身不阻止完成。
    boolean mustSettle() {
        return active && (settling()
                || !live().isEmpty()
                || !unaccountedByItem().isEmpty()
                || hasUnreachableCurrentSweep());
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

    // 分别返回推定来源数量、背包确认增加、地上剩余、走不到、合堆与未解释的差额，不把它们统称为已收获。
    // 收场摘要与详细回执共用拾取账，只计算观察到待收堆叠且背包确实增加的物品，不要求证明击杀来源。
    Map<String, Integer> confirmedGains() {
        Map<Item, Integer> confirmed = new HashMap<>(collectedFromSettledSweeps);
        if (active) {
            for (var entry : sweepPickupExpected.entrySet()) {
                account(confirmed, entry.getKey(), sweepReceivedUnits.getOrDefault(entry.getKey(), 0));
            }
        }
        return named(confirmed);
    }

    Map<String, Object> report() {
        Map<Item, Integer> remaining = new HashMap<>();
        Map<Item, Integer> blocked = new HashMap<>();
        for (int id : tracked) {
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
        out.put("observed_pickup_units_by_item", named(observedPickupTotal));
        out.put("confirmed_inventory_gain_by_item", confirmedGains());
        out.put("observed_inventory_increases_by_item", named(currentSweepInventoryGain()));
        out.put("remaining_reachable_by_item", named(remaining));
        out.put("remaining_loaded_but_unreached_by_item", named(blocked));
        out.put("unreachable_by_item", named(unreachableByItem));
        out.put("ambiguous_merged_by_item", named(ambiguousByItem));
        out.put("local_player_drop_candidates_by_item", named(observedLocalPlayerDrops));
        out.put("pickup_scope", "loaded death-site candidates regardless of ownership; pickup gains do not prove kill origin");
        out.put("preexisting_stack_disappeared_by_item", named(vanishedPreexistingByItem));
        out.put("unconfirmed_vanished_by_item", named(unaccountedByItem()));
        out.put("unresolved_candidate_evidence", unresolvedEvidence());
        out.put("death_site_count", deaths.size());
        out.put("active_sweep", active);
        out.put("attribution_window_open", active && settling());
        out.put("contact_evidence", contactEvidence());
        return out;
    }

    // 实际收取最多按本轮观察到的完整堆量记账；背包中其他来源的额外增加不直接放大拾取数量。
    private void settleCurrentSweepCollection() {
        for (var entry : sweepPickupExpected.entrySet()) {
            account(collectedFromSettledSweeps,
                    entry.getKey(), sweepReceivedUnits.getOrDefault(entry.getKey(), 0));
        }
    }

    private Map<Item, Integer> currentSweepInventoryGain() {
        Map<Item, Integer> out = new HashMap<>();
        for (Item item : sweepPickupExpected.keySet()) {
            int gain = Math.max(0,
                    inventoryCount(item) - inventoryAtSweepStart.getOrDefault(item, 0));
            if (gain > 0) out.put(item, gain);
        }
        return out;
    }

    private Map<Item, Integer> reachableTrackedLiveCounts() {
        Map<Item, Integer> out = new HashMap<>();
        for (int id : tracked) {
            if (blockedApproaches.containsKey(id)) continue;
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
            Entity entity = player.clientLevel.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved()) {
                account(out, item.getItem().getItem(), item.getItem().getCount());
            }
        }
        return out;
    }

    // 待收的整堆数量扣掉同步背包、地上剩余和不可达部分；来源不明不能替代真实拾取回执。
    private Map<Item, Integer> unaccountedByItem() {
        Map<Item, Integer> gains = sweepReceivedUnits;
        Map<Item, Integer> liveCounts = allTrackedLiveCounts();
        Map<Item, Integer> out = new HashMap<>();
        for (var entry : sweepPickupExpected.entrySet()) {
            int missing = entry.getValue()
                    - gains.getOrDefault(entry.getKey(), 0)
                    - liveCounts.getOrDefault(entry.getKey(), 0)
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

    // 按完整物品编号排序后生成可报告的数量表，保证同样的结果每次输出顺序一致。
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
