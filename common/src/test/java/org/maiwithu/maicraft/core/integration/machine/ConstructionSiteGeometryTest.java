// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

/** 密集地层不挤掉平台和障碍；观察时刻不改变模型收到的场地几何。 */
public final class ConstructionSiteGeometryTest {
    public static void main(String[] args) {
        JsonObject source = JsonParser.parseString("""
                {"structure_complete":true,"unloaded_cell_count":0,"outside_world_cell_count":0,
                 "construction_site":true,"palette":[
                 {"block_id":"minecraft:stone","properties":{}},
                 {"block_id":"minecraft:smooth_stone","properties":{}},
                 {"block_id":"minecraft:barrel","properties":{"facing":"up"}}]}
                """).getAsJsonObject();
        JsonArray cells = new JsonArray();
        for (int y = -8; y <= -1; y++) for (int z = -8; z <= 8; z++) for (int x = -8; x <= 8; x++)
            cells.add(JsonParser.parseString("[" + x + "," + y + "," + z + "," + (y == -1 ? 1 : 0) + "]"));
        cells.add(JsonParser.parseString("[0,0,0,2]")); source.add("relative_blocks", cells);
        var snapshot = snapshot(source);
        JsonObject result = ConstructionSiteGeometry.describe(snapshot);
        JsonArray rows = result.getAsJsonArray("surface_and_obstacles");
        check(rows.size() == 18, "seventeen floor runs and one real obstacle survive dense underground geometry");
        check(result.getAsJsonArray("palette").size() == 2 && result.toString().contains("barrel"), "only displayed states enter the planning palette");
        check(rows.get(0).toString().equals("[-1,-8,-8,8,0]") && rows.get(17).toString().equals("[0,0,0,0,1]"), "inclusive ranges preserve exact relative positions");
        check(snapshot.report().getAsJsonArray("relative_blocks").size() == 2313, "projection leaves the complete cached geometry intact");
        // 同一场地的几何呈现不受观察时刻影响；实际结构变化的操作校验由世界运行测试覆盖。
        var later = new MachineSnapshots.Snapshot(snapshot.id(),snapshot.label(),snapshot.dimension(),snapshot.center(),
                snapshot.radius(),5000,snapshot.fingerprint(),snapshot.reportJson());
        check(ConstructionSiteGeometry.describe(later).equals(result), "observation time does not alter the reusable site geometry");
        source.remove("construction_site");
        check(ConstructionSiteGeometry.describe(snapshot(source)).equals(result), "ordinary observations expose the same measured geometry");
        source.addProperty("structure_complete", false);
        check(!ConstructionSiteGeometry.describe(snapshot(source)).get("structure_complete").getAsBoolean(), "unloaded observations are never promoted to complete");
        System.out.println("ConstructionSiteGeometryTest: passed");
    }

    private static MachineSnapshots.Snapshot snapshot(JsonObject source) {
        return new MachineSnapshots.Snapshot("site", "platform", "minecraft:overworld", new BlockPos(20, 95, 30), 8, 100, "fingerprint", source.toString());
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
