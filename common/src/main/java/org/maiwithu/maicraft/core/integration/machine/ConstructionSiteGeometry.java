// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把完整勘察转换为地面与上方障碍的连续行；模型决定布局，原始三维快照留在 Mod 内供施工核对。 */
public final class ConstructionSiteGeometry {
    private ConstructionSiteGeometry() {}

    public static JsonObject describe(MachineSnapshots.Snapshot snapshot) {
        JsonObject raw = snapshot.report(), result = new JsonObject();
        result.addProperty("snapshot_id", snapshot.id());
        result.addProperty("dimension", snapshot.dimension());
        result.add("anchor", xyz(snapshot.center().getX(), snapshot.center().getY(), snapshot.center().getZ()));
        JsonObject target = new JsonObject();
        target.addProperty("kind", "landmark"); target.addProperty("label", snapshot.label());
        result.add("target", target);
        JsonObject bounds = new JsonObject();
        bounds.add("min", xyz(-snapshot.radius(), -snapshot.radius(), -snapshot.radius()));
        bounds.add("max", xyz(snapshot.radius(), snapshot.radius(), snapshot.radius()));
        result.add("observed_bounds", bounds);
        for (String field : List.of("structure_complete", "unloaded_cell_count", "outside_world_cell_count", "validity"))
            if (raw.has(field)) result.add(field, raw.get(field).deepCopy());
        // 只呈现脚下层及上方；下方地层仍在快照内，不把整片地下矿石当作建造前必读信息。
        List<JsonArray> cells = new ArrayList<>();
        for (var value : raw.getAsJsonArray("relative_blocks")) {
            JsonArray cell = value.getAsJsonArray();
            if (cell.get(1).getAsInt() >= -1) cells.add(cell);
        }
        cells.sort(Comparator.comparingInt((JsonArray cell) -> cell.get(1).getAsInt())
                .thenComparingInt(cell -> cell.get(2).getAsInt()).thenComparingInt(cell -> cell.get(0).getAsInt()));
        Map<Integer, Integer> indices = new LinkedHashMap<>();
        JsonArray palette = new JsonArray(), runs = new JsonArray();
        JsonArray previous = null;
        for (JsonArray cell : cells) {
            int old = cell.get(3).getAsInt();
            if (!indices.containsKey(old)) {
                indices.put(old, palette.size());
                JsonObject entry = raw.getAsJsonArray("palette").get(old).getAsJsonObject();
                JsonObject state = new JsonObject();
                state.add("block_id", entry.get("block_id").deepCopy());
                state.add("properties", entry.get("properties").deepCopy());
                palette.add(state);
            }
            int x = cell.get(0).getAsInt(), y = cell.get(1).getAsInt(), z = cell.get(2).getAsInt(), state = indices.get(old);
            if (previous != null && previous.get(0).getAsInt() == y && previous.get(1).getAsInt() == z
                    && previous.get(3).getAsInt() + 1 == x && previous.get(4).getAsInt() == state) {
                previous.set(3, new JsonPrimitive(x));
            } else {
                previous = xyz(y, z, x); previous.add(x); previous.add(state); runs.add(previous);
            }
        }
        result.add("palette", palette); result.add("surface_and_obstacles", runs);
        result.addProperty("geometry_format", "[offset_y,offset_z,first_x,last_x,palette_index], inclusive horizontal runs; all offsets relative to anchor; only y>=-1 shown");
        result.addProperty("geometry_scope", "Bounded observed volume, not a claim about the whole platform. Unlisted cells in the shown layers are air only when structure_complete=true. Choose the footprint from this geometry and the player's limits.");
        // 动力接口与控制线属于已观察信息；只保留组件和连接，不带车辆推断与整套分析规则。
        if (raw.has("control_analysis")) {
            JsonObject controls = raw.getAsJsonObject("control_analysis"), interfaces = new JsonObject();
            for (String key : List.of("complete", "components", "connections"))
                if (controls.has(key)) interfaces.add(key, controls.get(key).deepCopy());
            result.add("observed_interfaces", interfaces);
        }
        result.addProperty("next_step", "Generate the requested blueprint, then plan build_machine using target and snapshot_id above. The Mod supplies materials and checks the full construction footprint. Only revisit observations for a concrete diagnostic or changed geometry.");
        result.addProperty("observation_only", true);
        return result;
    }

    private static JsonArray xyz(int x, int y, int z) {
        JsonArray row = new JsonArray(); row.add(x); row.add(y); row.add(z); return row;
    }
}
