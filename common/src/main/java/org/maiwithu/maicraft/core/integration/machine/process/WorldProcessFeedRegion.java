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
import org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropRegion;

/** 普通投料落入已审查流体格；最后触发物还必须能按原生搜集范围触及仍然存在的全部本批原料。 */
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

    static boolean permitsCurrentThrow(LocalPlayer player, WorldProcessRecipe recipe, List<BlockPos> cells,
                                       WorldProcessInputs inputs, BlockPos receiver) {
        try {
            // 选主手和转头会跨游戏刻；真正按Q之前再读原料当前位置，只证明实际视角仍可投，不改变视角或放宽旧域。
            var present = inputs.requirePresent();
            var current = forInput(player, recipe, cells, inputs.entities(), present, true);
            return TargetedDropGeometry.safeActualView(player, receiver, current);
        } catch (RuntimeException changed) {
            // 原料漂走、被捡走或落点不可达时保留触发材料；这一只读守卫不能自行补料或消费。
            return false;
        }
    }
}
