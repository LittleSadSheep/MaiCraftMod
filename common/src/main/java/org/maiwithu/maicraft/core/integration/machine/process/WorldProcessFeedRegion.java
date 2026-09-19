// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropRegion;

/** 根据当前真实原料计算反应场地与瞄准偏好；原生延迟反应期间会漂移，不能把此交集当成首次入水的硬边界。 */
final class WorldProcessFeedRegion {
    private WorldProcessFeedRegion() {}

    static void requireRule(WorldProcessRecipe recipe) {
        if (recipe.inputs().size() < 2) return;
        double radius = recipe.inputSearchRadius().orElseThrow(() -> new IllegalArgumentException("world_process_native_input_region_unknown"));
        if (!Double.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("world_process_native_input_region_invalid");
    }

    static TargetedDropRegion forInput(LocalPlayer player, WorldProcessRecipe recipe, List<BlockPos> cells,
                                      Map<UUID, Integer> owned, List<ItemEntityReceipts.ObservedDrop> drops, boolean trigger) {
        TargetedDropRegion region = TargetedDropRegion.ofCells(cells);
        if (!trigger || recipe.inputs().size() < 2) return region;
        requireRule(recipe);
        if (owned.isEmpty() || drops.size() != owned.size()) throw new IllegalStateException("world_process_trigger_inputs_missing");
        double radius = recipe.inputSearchRadius().orElseThrow();
        for (var drop : drops) {
            var entity = player.level().getEntity(drop.entityId());
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || !item.getUUID().equals(drop.uuid())
                    || owned.getOrDefault(drop.uuid(), 0) != drop.stack().getCount() || !ItemStack.matches(item.getItem(), drop.stack()))
                throw new IllegalStateException("world_process_trigger_inputs_changed");
            // AE2原生查找以触发物底部position为中心各±radius，不含触发物自身宽高；用当前原料bbox反推坐标。
            // 原版AABB相交是严格不等式，边界相切不能算能收集，故将允许坐标向内收一个浮点单位。
            region = region.narrowTo(triggerPositions(item.getBoundingBox(), radius));
        }
        return region;
    }

    static AABB triggerPositions(AABB input, double radius) {
        return new AABB(Math.nextUp(input.minX - radius), Math.nextUp(input.minY - radius), Math.nextUp(input.minZ - radius),
                Math.nextDown(input.maxX + radius), Math.nextDown(input.maxY + radius), Math.nextDown(input.maxZ + radius));
    }

    static TargetedDropRegion currentAim(LocalPlayer player, WorldProcessRecipe recipe, List<BlockPos> cells, WorldProcessInputs inputs) {
        // 选主手与转头会跨刻；每次重新读取仍活着的本批原料，组件、数量和可反应交集变化会更新瞄准或阻止出手。
        var present = inputs.requirePresent();
        return forInput(player, recipe, cells, inputs.entities(), present, true);
    }

    static boolean permitsCurrentThrow(LocalPlayer player, WorldProcessRecipe recipe, List<BlockPos> cells, WorldProcessInputs inputs) {
        try {
            // AE2累计的是有效流体刻数，超过60刻才按届时位置取料；这里仅证原料与可反应场地仍存在，不预告已反应。
            return !currentAim(player, recipe, cells, inputs).cells().isEmpty();
        } catch (RuntimeException changed) {
            // 原料被捡走、组件变化或已无可反应交集时保留触发材料；这一只读守卫不能自行补料或消费。
            return false;
        }
    }
}
