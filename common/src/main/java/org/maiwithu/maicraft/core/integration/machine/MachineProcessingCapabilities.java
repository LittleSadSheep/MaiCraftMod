// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** 设备适配器给出的工件接口；位置和净空属于原生交互规则，配方和产量仍须单独证明。 */
public record MachineProcessingCapabilities(boolean processor, boolean surface, boolean moving,
        BlockPos workOffset, List<BlockPos> clearance, Map<String, String> requiredState, String issue) {
    public MachineProcessingCapabilities {
        workOffset = workOffset.immutable(); clearance = clearance.stream().map(BlockPos::immutable).toList(); requiredState = Map.copyOf(requiredState);
    }
    public static MachineProcessingCapabilities unavailable(String reason) {
        return new MachineProcessingCapabilities(false, false, false, BlockPos.ZERO, List.of(), Map.of(), reason);
    }
    public JsonObject json() {
        JsonObject result = new JsonObject(); result.addProperty("external_workpiece_processor", issue == null ? processor : null);
        result.addProperty("external_workpiece_surface", issue == null ? surface : null); result.addProperty("surface_transports_workpieces", issue == null && surface ? moving : null);
        JsonObject state = new JsonObject(); requiredState.forEach(state::addProperty); result.add("required_processing_state", state);
        if (processor) {
            result.add("work_position_offset", offset(workOffset));
            JsonArray gaps = new JsonArray(); clearance.forEach(at -> gaps.add(offset(at)));
            result.add("clearance_offsets", gaps); result.addProperty("processing_protocol", "receive/hold/pass/remove");
        }
        result.addProperty("recipe_verified", false); result.addProperty("throughput_verified", false);
        if (issue != null) result.addProperty("unknown", issue);
        return result;
    }
    private static JsonArray offset(BlockPos at) { JsonArray row = new JsonArray(); row.add(at.getX()); row.add(at.getY()); row.add(at.getZ()); return row; }
}
