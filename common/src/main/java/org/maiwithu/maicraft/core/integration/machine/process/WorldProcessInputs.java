// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.task.TaskState;

/** 本批投料只认已经确认的原生回执；之后允许完整物品守恒的合堆，不要求加工后原料实体继续存在。 */
final class WorldProcessInputs {
    private final LocalPlayer player;
    private final WorldProcessRecipe recipe;
    private final WorldProcessInventory inventory;
    private final AABB region;
    private final long cursor;
    private final Map<UUID, Integer> entities = new LinkedHashMap<>();
    private final Map<UUID, Integer> entityIds = new LinkedHashMap<>();
    private final List<ItemStack> delivered = new ArrayList<>();

    WorldProcessInputs(LocalPlayer player, WorldProcessRecipe recipe, WorldProcessInventory inventory, AABB region, long cursor) {
        this.player = player; this.recipe = recipe; this.inventory = inventory; this.region = region; this.cursor = cursor;
    }

    Map<UUID, Integer> entities() { return Map.copyOf(entities); }
    List<ItemStack> delivered() { return delivered.stream().map(ItemStack::copy).toList(); }

    void confirmDrop(TaskState state, Map<String, Object> data, ItemStack input) {
        // 父任务超时时子任务收尾仍可读到已APPLIED的冻结回执；只接收明确无歧义的完整历史投料，绝不再出手。
        boolean terminalReceipt = state == TaskState.SUCCESS || state == TaskState.TIMEOUT
                && data != null && Boolean.FALSE.equals(data.get("outcome_uncertain"));
        if (!terminalReceipt || data == null || !(data.get("actual_removed_count") instanceof Number removed)
                || removed.intValue() != input.getCount() || !(data.get("confirmed_received_count") instanceof Number received)
                || received.intValue() != input.getCount()) throw new IllegalStateException("world_process_drop_receipt_incomplete");
        // 先验证全部实体身份和组件，再推进物资账；失败或只有预计移出量的子任务不能成为加工原料。
        var credits = WorldProcessDropReceipt.read(data, input, player.registryAccess());
        inventory.dropped(input, input.getCount()); delivered.add(input.copy());
        for (var credit : credits) {
            entities.merge(credit.uuid(), credit.amount(), Math::addExact); entityIds.put(credit.uuid(), credit.entityId());
        }
    }

    List<ItemEntityReceipts.ObservedDrop> requirePresent() {
        var drops = ItemEntityReceipts.snapshot(player, region);
        if (!WorldProcessDropReceipt.sameItems(delivered, drops.stream().map(ItemEntityReceipts.ObservedDrop::stack).toList()))
            throw new IllegalStateException("world_process_input_entities_changed");
        for (var drop : drops) if (!ItemEntityReceipts.spawnedAfter(player, drop.uuid(), cursor)
                || !recipe.supports(player.level().getFluidState(BlockPos.containing(drop.position()))))
            throw new IllegalStateException("world_process_input_missed_environment");
        // 原版合堆可以保留不同UUID；只有本批组件和总量守恒的实体可以重绑，不能认领整池同类物品。
        entities.clear(); entityIds.clear();
        for (var drop : drops) { entities.put(drop.uuid(), drop.stack().getCount()); entityIds.put(drop.uuid(), drop.entityId()); }
        return drops;
    }

    boolean gone() {
        for (var entry : entityIds.entrySet()) {
            var entity = player.level().getEntity(entry.getValue());
            if (entity != null && entity.getUUID().equals(entry.getKey()) && !entity.isRemoved()) return false;
        }
        return true;
    }

    void requireNoForeignDrops(List<ItemEntityReceipts.ObservedDrop> drops, WorldProcessEventEvidence.Output output) {
        for (var drop : drops) {
            if (entities.containsKey(drop.uuid())) continue;
            boolean expected = output != null ? output.uuid().equals(drop.uuid())
                    : ItemEntityReceipts.spawnedAfter(player, drop.uuid(), cursor) && drop.stack().is(recipe.result().getItem());
            if (!expected) throw new IllegalStateException("world_process_foreign_item_entered_receiver");
        }
    }
}
