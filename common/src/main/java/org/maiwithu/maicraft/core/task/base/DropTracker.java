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
 * "只捡这次战果"的掉落物簿记:先快照现场已有的物品实体(id 与堆叠数),
 * 事后按差集发现新掉落——新 id 是新掉落,旧 id 堆叠数变大(掉落并入了
 * 已有实体)也是。近战的战利品相位与钓鱼的收获相位共用这份发现/追踪
 * 逻辑;两者怎么走过去捡、捡不到算不算失败,是各自的产品语义,留在
 * 任务里。
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
    public int receivedTrackedUnits(Player player) {
        if (!inventoryRemembered) return 0;
        return new HashSet<>(observedItems.values()).stream()
                .mapToInt(item -> Math.max(0,
                        PlayerInv.carriedCount(player.getInventory(), item)
                                - inventoryBaseline.getOrDefault(item, 0)))
                .sum();
    }

    /** Fallback receipt for a drop absorbed before it was ever render-visible. */
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
