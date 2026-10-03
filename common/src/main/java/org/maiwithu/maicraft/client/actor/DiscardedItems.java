// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts.ObservedDrop;

/** 主动丢弃后跟踪真实实体的拾取范围；后续任务绕开这些物品，直到消失、被收走或离开当前世界。 */
public final class DiscardedItems {
    private static LocalPlayer owner;
    private static ClientLevel world;
    private static long lastTick;
    private static final List<Watch> pending = new ArrayList<>();
    private static final Map<UUID, Tracked> tracked = new LinkedHashMap<>();
    private static LongSet forbidden = LongSets.emptySet();
    private DiscardedItems() {}

    public static final class Watch {
        private final ItemStack kind;
        private final int amount;
        private final AABB area;
        private final long cursor, deadline;
        private final Map<UUID, ObservedDrop> before;
        private final Map<UUID, ObservedDrop> observed = new LinkedHashMap<>();
        private Watch(LocalPlayer player, ItemStack stack, int amount) {
            this.kind = stack.copy(); this.amount = amount;
            area = player.getBoundingBox().inflate(8); deadline = player.level().getGameTime() + 100;
            before = snapshot(player, area); cursor = ItemEntityReceipts.cursor(player);
        }
        /** 点火收场只处理这一批已经观察到的物品，合并后的接收堆也沿同一归属继续观察。 */
        public List<ObservedDrop> remaining() {
            return tracked.values().stream().filter(value -> value.watches.contains(this)).map(value -> value.drop).toList();
        }
        public boolean pending() { return pending.contains(this); }
        public boolean observed() { return !observed.isEmpty(); }
        public Map<String, Object> result() {
            // 库存扣减与地上实体证据分别报告；未观察到落点时明确保留未知，不能编一个预计位置当作真实落点。
            return Map.of("amount", amount, "entity_observed", !observed.isEmpty(), "observation_pending", pending.contains(this),
                    "entities", observed.values().stream().map(drop -> {
                        var live = tracked.get(drop.uuid()); var position = live == null ? drop.position() : live.drop.position();
                        return Map.of("uuid", drop.uuid().toString(), "position", List.of(position.x, position.y, position.z),
                                "avoided", live != null);
                    }).toList());
        }
    }

    private static final class Tracked {
        ObservedDrop drop;
        AABB box;
        Map<UUID, ObservedDrop> neighbors;
        long seen;
        boolean mixed;
        final Set<Watch> watches = new LinkedHashSet<>();
        Tracked(LocalPlayer player, ObservedDrop drop, ItemEntity entity) { update(player, drop, entity); }
        void update(LocalPlayer player, ObservedDrop next, ItemEntity entity) {
            drop = next; box = entity.getBoundingBox(); seen = player.level().getGameTime();
            neighbors = snapshot(player, box.inflate(1));
        }
    }

    public static Watch watch(LocalPlayer player, ItemStack stack, int amount) {
        observe(player);
        var watch = new Watch(player, stack, amount); pending.add(watch); return watch;
    }

    /** 客户端每刻都继续观察，包括任务已取消或玩家临时接管；换身体、换世界和倒退的时间立即隔离旧现场。 */
    public static void observe(LocalPlayer player) {
        ClientLevel level = player == null ? null : player.clientLevel;
        long now = level == null ? 0 : level.getGameTime();
        if (owner != player || world != level || now < lastTick) {
            pending.clear(); tracked.clear(); forbidden = LongSets.emptySet(); owner = player; world = level;
        }
        lastTick = now;
        if (level == null) return;
        for (var watch : List.copyOf(pending)) {
            int credited = 0;
            for (var drop : snapshot(player, watch.area).values()) {
                if (!ItemStack.isSameItemSameComponents(drop.stack(), watch.kind)) continue;
                var previous = watch.before.get(drop.uuid());
                if (previous == null && !ItemEntityReceipts.spawnedAfter(player, drop.uuid(), watch.cursor)) continue;
                if (previous != null && !ItemStack.isSameItemSameComponents(previous.stack(), watch.kind)) continue;
                int increase = drop.stack().getCount() - (previous == null ? 0 : previous.stack().getCount());
                if (increase <= 0) continue;
                credited += increase; watch.observed.put(drop.uuid(), drop);
                boolean preexisting = previous != null && previous.stack().getCount() > 0 && !tracked.containsKey(drop.uuid());
                var value = remember(player, drop);
                if (value != null) { value.watches.add(watch); value.mixed |= preexisting || increase > watch.amount; }
            }
            if (credited >= watch.amount || now >= watch.deadline) pending.remove(watch);
        }
        for (var entry : List.copyOf(tracked.entrySet())) {
            var value = entry.getValue(); var entity = level.getEntity(value.drop.entityId());
            // 区块重载可能更换临时实体编号；UUID 仍是同一件丢弃物时重新绑定，不能把编号变化当成已消失。
            if (entity == null || !entity.getUUID().equals(entry.getKey())) {
                var same = snapshot(player, value.box.inflate(1)).get(entry.getKey());
                if (same != null) entity = level.getEntity(same.entityId());
            }
            if (entity instanceof ItemEntity item && !item.isRemoved() && !item.getItem().isEmpty()
                    && item.getUUID().equals(entry.getKey())) {
                value.update(player, new ObservedDrop(item.getId(), item.getUUID(), item.getItem(), item.position()), item);
                continue;
            }
            // 区块暂时卸载不等于物品消失；加载现场中实体合堆时，把避让交给数量实际增长的接收堆。
            if (!level.isLoaded(BlockPos.containing(value.drop.position()))) continue;
            boolean merged = false;
            for (var candidate : snapshot(player, value.box.inflate(1)).values()) {
                if (candidate.uuid().equals(entry.getKey()) || !ItemStack.isSameItemSameComponents(candidate.stack(), value.drop.stack())) continue;
                var previous = value.neighbors.get(candidate.uuid());
                if (candidate.stack().getCount() > (previous == null ? 0 : previous.stack().getCount())) {
                    boolean untrackedRemainder = previous != null && previous.stack().getCount() > 0 && !tracked.containsKey(candidate.uuid());
                    var receiver = remember(player, candidate);
                    if (receiver != null) receiver.mixed |= value.mixed || untrackedRemainder;
                    if (receiver != null) for (var watch : value.watches) {
                        receiver.watches.add(watch); watch.observed.put(candidate.uuid(), candidate);
                    }
                    merged = true;
                }
            }
            // 实体移除与接收堆增量可能分包到达，短暂保留最后现场，不能一收到移除包就让路径穿过合堆位置。
            if (merged || now - value.seen > 5) tracked.remove(entry.getKey());
        }
        var cells = new LongOpenHashSet();
        for (var value : tracked.values()) addPickupCells(cells, value.box,
                player.getBoundingBox().getXsize(), player.getBoundingBox().getYsize());
        forbidden = LongSets.unmodifiable(cells);
    }

    private static Tracked remember(LocalPlayer player, ObservedDrop drop) {
        if (!(world.getEntity(drop.entityId()) instanceof ItemEntity item) || !item.getUUID().equals(drop.uuid())) return null;
        var value = tracked.get(drop.uuid());
        if (value == null) { value = new Tracked(player, drop, item); tracked.put(drop.uuid(), value); }
        else value.update(player, drop, item);
        return value;
    }

    private static Map<UUID, ObservedDrop> snapshot(LocalPlayer player, AABB area) {
        var result = new LinkedHashMap<UUID, ObservedDrop>();
        for (var drop : ItemEntityReceipts.snapshot(player, area)) result.put(drop.uuid(), drop);
        return result;
    }

    /** 原版拾取使用玩家包围盒横向扩一格、纵向半格；再计入角色半宽，不能漏掉斜向角落里的自动拾取。 */
    public static void addPickupCells(LongSet cells, AABB item, double playerWidth, double playerHeight) {
        // 导航格表示脚位，所以竖直下界还要减去身高，防止从台阶下方走过时头顶进入拾取盒。
        AABB pickup = item.inflate(1 + playerWidth / 2, .5, 1 + playerWidth / 2).expandTowards(0, -playerHeight, 0);
        for (int x = Mth.floor(pickup.minX); x <= Mth.floor(pickup.maxX); x++)
            for (int y = Mth.floor(pickup.minY); y <= Mth.floor(pickup.maxY); y++)
                for (int z = Mth.floor(pickup.minZ); z <= Mth.floor(pickup.maxZ); z++) cells.add(BlockPos.asLong(x, y, z));
    }

    /** 只交付不可变格集给导航；异步搜索继续使用既有冻结快照，不读取客户端实体对象。 */
    public static LongSet forbiddenBodyCells() { return forbidden; }

    /** 合入了现场其他物品的堆仍需避让，但不能因其中有本次垃圾就授权整堆烧毁。 */
    public static boolean burnable(ObservedDrop drop) {
        var value = tracked.get(drop.uuid()); return value != null && !value.mixed;
    }
}
