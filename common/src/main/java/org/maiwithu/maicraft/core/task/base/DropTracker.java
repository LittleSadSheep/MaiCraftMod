package org.maiwithu.maicraft.core.task.base;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.PlayerInv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对照前后变化，追踪新出现或数量增加的地上物品。
 * 目前用于钓鱼和临时工作台回收；战斗有另一套 LootSweep。它只整理观察数据，不证明物品属于谁，也不负责控制拾取。
 */
public final class DropTracker {

    private final Set<Integer> preexisting = new HashSet<>();
    private final Map<Integer, Integer> preexistingCounts = new HashMap<>();
    private final Set<Integer> tracked = new LinkedHashSet<>();
    /** First-seen type and largest attributable unit count survive entity pruning,
     *  so a later inventory packet can still prove where a vanished drop went. */
    private final Map<Integer, Item> observedItems = new HashMap<>();
    private final Map<Integer, Integer> observedCounts = new HashMap<>();
    private final Map<Item, Integer> inventoryBaseline = new HashMap<>();
    private int inventoryUnitsBefore;
    private boolean inventoryRemembered;

    /** 快照 {@code box} 内现有物品实体——之后的 discover 只认快照外的新面孔。 */
    // 记住观察范围里原来存在的物品编号和数量，后来用来区分新实体或旧堆增长。
    public void rememberExisting(Level level, AABB box) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            preexisting.add(item.getId());
            preexistingCounts.put(item.getId(), item.getItem().getCount());
        }
    }

    /** Snapshot the authoritative main inventory before the causal drop-producing action. */
    public void rememberInventory(Player player) {
        inventoryBaseline.clear();
        inventoryUnitsBefore = 0;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            var stack = player.getInventory().items.get(slot);
            if (stack.isEmpty()) continue;
            inventoryBaseline.merge(stack.getItem(), stack.getCount(), Integer::sum);
            inventoryUnitsBefore += stack.getCount();
        }
        inventoryRemembered = true;
    }

    /** 把 {@code box} 内快照之外(或堆叠数长了)的物品实体收入追踪。 */
    // 新编号计整堆，旧编号只把超过原数量的部分计作新增。
    // 但下面 live 返回的仍是整堆实体，实际靠近会连旧物品一起捡，计数差额不等于拾取范围（A68）。
    public void discover(Level level, AABB box) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            int id = item.getId();
            int current = item.getItem().getCount();
            int attributable = preexisting.contains(id)
                    ? Math.max(0, current - preexistingCounts.getOrDefault(id, 0))
                    : current;
            if (attributable > 0) {
                tracked.add(id);
                observedItems.putIfAbsent(id, item.getItem().getItem());
                observedCounts.merge(id, attributable, Math::max);
            }
        }
    }

    /** 清掉已被拾取/消失的追踪项。 */
    public void prune(ClientLevel level) {
        tracked.removeIf(id -> {
            Entity entity = level.getEntity(id);
            return !(entity instanceof ItemEntity) || entity.isRemoved();
        });
    }

    /** 仍在世且未被放弃({@code skipped})的追踪掉落物。 */
    // 只返回还存在且未被调用方跳过的实体，不在这里限制能拾取其中多少件。
    public List<ItemEntity> live(ClientLevel level, Set<Integer> skipped) {
        List<ItemEntity> out = new ArrayList<>();
        for (int id : tracked) {
            Entity entity = level.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved() && !skipped.contains(id)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 离身体最近的在世追踪掉落物。 */
    public Optional<ItemEntity> nearest(ClientLevel level, Player player, Set<Integer> skipped) {
        return live(level, skipped).stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    /** 是否有任何在世追踪项(含被 skip 的——它们还在地上,只是不去捡)。 */
    public boolean anyTrackedAlive(ClientLevel level) {
        return !live(level, Set.of()).isEmpty();
    }

    /** Number of causally attributed units represented by every observed drop id. */
    public int attributableUnits() {
        return observedCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean observedAny() {
        return !observedCounts.isEmpty();
    }

    /**
     * Positive synchronized main-inventory deltas for the item types this tracker
     * actually observed. This remains valid after {@link #prune} removes vanished
     * entity ids from the live set.
     */
    // 按曾观察到的物品种类累计背包正增量；同种物品若从别处增加，也会进入这份数值。
    public int receivedTrackedUnits(Player player) {
        if (!inventoryRemembered) return 0;
        return new HashSet<>(observedItems.values()).stream()
                .mapToInt(item -> Math.max(0,
                        PlayerInv.carriedCount(player.getInventory(), item)
                                - inventoryBaseline.getOrDefault(item, 0)))
                .sum();
    }

    /** Fallback receipt for a drop absorbed before it was ever render-visible. */
    // 所有主背包物品的总件数相比开始时增加多少；这不是某次掉落事件的来源证明。
    public int totalInventoryUnitGain(Player player) {
        if (!inventoryRemembered) return 0;
        int current = 0;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            var stack = player.getInventory().items.get(slot);
            if (!stack.isEmpty()) current += stack.getCount();
        }
        return Math.max(0, current - inventoryUnitsBefore);
    }

    /** 开始新一轮追踪(保留快照,清追踪集)。 */
    // 清掉本轮跟踪和背包基线，保留更早记录的地上旧物品名单；clear 才连那份名单一起清。
    public void resetTracking() {
        tracked.clear();
        observedItems.clear();
        observedCounts.clear();
        inventoryBaseline.clear();
        inventoryUnitsBefore = 0;
        inventoryRemembered = false;
    }

    /** 整体清空(快照与追踪集)。 */
    public void clear() {
        preexisting.clear();
        preexistingCounts.clear();
        tracked.clear();
        observedItems.clear();
        observedCounts.clear();
        inventoryBaseline.clear();
        inventoryUnitsBefore = 0;
        inventoryRemembered = false;
    }
}
