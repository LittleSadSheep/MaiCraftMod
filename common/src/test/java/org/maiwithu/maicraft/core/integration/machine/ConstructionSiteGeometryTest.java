// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

/** 密集地层不挤掉平台和障碍；长时间设计只延续场地定位，不改变普通机器回执的时效。 */
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
        check(!MachineSnapshots.expired(snapshot, 5000, true), "design duration alone does not invalidate the construction anchor");
        check(MachineSnapshots.expired(snapshot, 5000, false), "site receipts cannot extend permission for native machine operations");
        check(MachineSnapshots.expired(snapshot, 99, true), "reversed game time still invalidates a site");
        source.remove("construction_site");
        check(MachineSnapshots.expired(snapshot(source), 5000, true), "ordinary machine actions retain their short receipt lifetime");
        source.addProperty("structure_complete", false);
        check(!ConstructionSiteGeometry.describe(snapshot(source)).get("structure_complete").getAsBoolean(), "unloaded observations are never promoted to complete");
        System.out.println("ConstructionSiteGeometryTest: passed");
    }

    private static MachineSnapshots.Snapshot snapshot(JsonObject source) {
        return new MachineSnapshots.Snapshot("site", "platform", "minecraft:overworld", new BlockPos(20, 95, 30), 8, 100, "fingerprint", source.toString());
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
