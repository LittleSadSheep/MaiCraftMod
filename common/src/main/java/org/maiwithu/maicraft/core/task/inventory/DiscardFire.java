// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.DiscardedItems;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts.ObservedDrop;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.task.TaskState;

/** 真实落点 → 点火 → 等待实体消失 → 左键扑灭；耐火物和无法确认的销毁保留在通道外，不重复点火。 */
final class DiscardFire {
    private enum Phase { OBSERVE, IGNITE, WAIT, EXTINGUISH, DONE }
    private final List<DiscardedItems.Watch> watches;
    private final Set<BlockPos> attempted = new LinkedHashSet<>(), fires = new LinkedHashSet<>();
    private final Set<BlockPos> uncertainIgnitions = new LinkedHashSet<>();
    private final Set<UUID> attemptedDrops = new LinkedHashSet<>();
    private final List<String> issues = new ArrayList<>();
    private Phase phase = Phase.OBSERVE;
    private DiscardBlockAction action;
    private BlockPos cell;
    private long waitUntil, started = -1;
    private int ignitions, extinguished;
    private boolean queued;
    private final boolean allowIgnition;

    DiscardFire(List<DiscardedItems.Watch> watches) { this(watches, true); }
    DiscardFire(List<DiscardedItems.Watch> watches, boolean allowIgnition) { this.watches = List.copyOf(watches); this.allowIgnition = allowIgnition; }
    TaskState tick(LocalPlayerContext context) {
        DiscardedItems.observe(context.player());
        if (phase == Phase.DONE) return TaskState.SUCCESS;
        if (started < 0) started = context.level().getGameTime();
        if (action != null) {
            TaskState state = action.tick(context);
            if (state == TaskState.RUNNING) return state;
            action.close(context);
            if (state != TaskState.SUCCESS) issues.add(action.failure());
            if (phase == Phase.IGNITE && state != TaskState.SUCCESS && action.submitted()) uncertainIgnitions.add(cell);
            action = null;
            if (phase == Phase.IGNITE) {
                // 原生调用之后即使回执失配，也按实际火格收尾，不能用“没确认成功”作为留下火焰的理由。
                if (fire(context, cell)) { uncertainIgnitions.remove(cell); fires.add(cell); ignitions++; phase = Phase.WAIT; waitUntil = context.level().getGameTime() + 100; }
                else phase = Phase.OBSERVE;
            } else {
                if (!fire(context, cell)) { fires.remove(cell); extinguished++; }
                phase = Phase.OBSERVE;
            }
            return TaskState.RUNNING;
        }
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        if (phase == Phase.WAIT) {
            boolean present = remaining().stream().anyMatch(drop -> BlockPos.containing(drop.position()).equals(cell));
            if (present && fire(context, cell) && context.level().getGameTime() < waitUntil) return TaskState.RUNNING;
            if (present) issues.add("discarded_items_survived_or_left_the_fire_cell");
            phase = Phase.EXTINGUISH; action = new DiscardBlockAction(cell, DiscardBlockAction.Kind.EXTINGUISH);
            return TaskState.RUNNING;
        }
        if (!allowIgnition || PlayerInv.count(context.player().getInventory(), Items.FLINT_AND_STEEL) == 0) return done();
        if (watches.stream().anyMatch(DiscardedItems.Watch::pending)) return TaskState.RUNNING;
        boolean waitingForGround = false;
        for (var drop : remaining()) {
            BlockPos actual = BlockPos.containing(drop.position());
            if (attempted.contains(actual) || attemptedDrops.contains(drop.uuid())) continue;
            // 飘落尚未结束时不在半空乱点火；真实落地后才对它脚下的支撑面使用打火石。
            var entity = context.level().getEntity(drop.entityId());
            if (!(entity instanceof ItemEntity item) || !item.getUUID().equals(drop.uuid())) continue;
            if (!item.onGround()) { waitingForGround = true; continue; }
            attempted.add(actual);
            attemptedDrops.add(drop.uuid());
            if (!canBurn(context, actual, remaining())) { issues.add("fire_skipped_to_preserve_other_items_or_surroundings"); continue; }
            cell = actual; phase = Phase.IGNITE; action = new DiscardBlockAction(cell, DiscardBlockAction.Kind.IGNITE);
            return TaskState.RUNNING;
        }
        if (waitingForGround && context.level().getGameTime() - started < 100) return TaskState.RUNNING;
        if (waitingForGround) issues.add("no_settled_drop_observed_for_ignition");
        return done();
    }
    private TaskState done() { phase = Phase.DONE; return TaskState.SUCCESS; }
    List<ObservedDrop> remaining() {
        return watches.stream().flatMap(watch -> watch.remaining().stream()).collect(Collectors.toMap(
                ObservedDrop::uuid, drop -> drop, (left, right) -> right, LinkedHashMap::new)).values().stream().toList();
    }
    private static boolean canBurn(LocalPlayerContext context, BlockPos cell, List<ObservedDrop> drops) {
        if (!context.level().isLoaded(cell) || !context.level().getBlockState(cell).isAir()) return false;
        Set<UUID> disposable = new LinkedHashSet<>();
        for (var drop : drops) if (DiscardedItems.burnable(drop)) disposable.add(drop.uuid());
        for (var item : context.level().getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(.25)))
            if (!disposable.contains(item.getUUID())) return false;
        // 丢垃圾不包含烧房子或其他物品的授权；旁边有可燃物时留下已选好的侧袋，不扩大这次原生点火范围。
        for (BlockPos nearby : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 4, 1))) {
            if (!context.level().isLoaded(nearby) || context.level().getBlockState(nearby).ignitedByLava()) return false;
        }
        return true;
    }
    private static boolean fire(LocalPlayerContext context, BlockPos cell) {
        return cell != null && context.level().isLoaded(cell) && context.level().getBlockState(cell).getBlock() instanceof BaseFireBlock;
    }
    Map<String, Object> result() {
        return Map.of("ignitions_observed", ignitions, "fires_extinguished", extinguished,
                "remaining_discarded_entities", remaining().size(), "all_landings_observed", watches.stream().allMatch(DiscardedItems.Watch::observed),
                "remaining_fire_cells", fires.stream().map(pos -> List.of(pos.getX(), pos.getY(), pos.getZ())).toList(),
                "cleanup_queued", queued, "uncertain_ignition_cells", uncertainIgnitions.stream().map(pos -> List.of(pos.getX(), pos.getY(), pos.getZ())).toList(),
                "issues", List.copyOf(issues));
    }
    void close(LocalPlayerContext context) {
        if (action != null) {
            if (phase == Phase.IGNITE && action.submitted()) uncertainIgnitions.add(cell);
            action.close(context);
        }
        if (fire(context, cell)) fires.add(cell);
        if (!fires.isEmpty()) { DiscardFireCleanup.enqueue(context.player(), fires); queued = true; }
        uncertainIgnitions.removeAll(fires);
        if (!uncertainIgnitions.isEmpty()) { DiscardFireCleanup.awaitLateFire(context.player(), uncertainIgnitions); queued = true; }
    }
}
