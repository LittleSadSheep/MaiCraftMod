// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** 把选定机器范围内的实际地图导出成蓝图式布局；绝不拿存档设计中的方块或状态补齐现状。 */
public final class MachineWorldBlueprint {
    private MachineWorldBlueprint() {}
    public static JsonObject page(Level world, String dimension, BlockPos anchor, BlockPos minimum, BlockPos maximum, int offset, int limit) {
        long width = (long) maximum.getX() - minimum.getX() + 1, height = (long) maximum.getY() - minimum.getY() + 1;
        long length = (long) maximum.getZ() - minimum.getZ() + 1;
        if (width < 1 || height < 1 || length < 1 || offset < 0 || limit < 1) throw new IllegalArgumentException("invalid_machine_capture_range");
        long total = Math.multiplyExact(Math.multiplyExact(width,height),length);
        long start = Math.min(total,offset), end = Math.min(total,start + limit);
        var blocks = new JsonArray(); var unknown = new JsonArray(); int air = 0;
        boolean sameDimension = world.dimension().location().toString().equals(dimension);
        for (long index = start; index < end; index++) {
            var at = new BlockPos(minimum.getX() + (int) (index % width), minimum.getY() + (int) (index / (width * length)),
                    minimum.getZ() + (int) (index / width % length));
            // 未加载区域只列未知格子，不能把设计中的旧方块复制过来，也不能假装它现在是空气。
            if (!sameDimension || !world.isLoaded(at)) {
                var row = new JsonObject(); row.add("offset",vector(at.subtract(anchor)));
                row.addProperty("reason",sameDimension ? "chunk_not_loaded" : "different_dimension"); unknown.add(row); continue;
            }
            var actual = world.getBlockState(at);
            if (actual.isAir()) { air++; continue; }
            var row = MachineBlueprintDiff.state(actual); row.add("offset",vector(at.subtract(anchor))); blocks.add(row);
        }
        var result = new JsonObject(); result.addProperty("schema","maicraft.observed_blueprint.v1");
        result.addProperty("data_source","current_client_world"); result.addProperty("dimension",dimension);
        result.add("anchor",vector(anchor)); result.add("min_offset",vector(minimum.subtract(anchor))); result.add("max_offset",vector(maximum.subtract(anchor)));
        result.addProperty("observed_at_tick",world.getGameTime()); result.addProperty("total_cells",total);
        result.addProperty("offset",start); result.addProperty("examined",end - start); result.addProperty("air_cells",air);
        result.add("blocks",blocks); result.add("unknown_cells",unknown); result.addProperty("has_more",end < total);
        if (end < total) result.addProperty("next_offset",end);
        result.addProperty("capture_complete",start == 0 && end == total && unknown.isEmpty());
        result.addProperty("scope","actual_block_ids_and_states_in_capture_bounds; inventories_and_configuration_are_separate_observations");
        return result;
    }
    private static JsonArray vector(BlockPos at) { var result = new JsonArray(); result.add(at.getX()); result.add(at.getY()); result.add(at.getZ()); return result; }
}
