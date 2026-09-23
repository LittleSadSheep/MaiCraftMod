// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;

/** 物品先向北再向东，拐角只有接收带占格；自动分段不会假设跨角已经接通动力。 */
public final class MachineBeltRoutesTest {
    public static void main(String[] args) {
        JsonObject input = JsonParser.parseString("""
                {"blocks":[],"assembly":{"installations":[{"type":"create:belt","path":[[0,0,5],[0,0,0],[8,0,0]],"pulleys":[[3,0,0]]}]}}
                """).getAsJsonObject();
        var compiled = MachineBlueprintDocument.compile(input, MachineBeltAssemblyTest.REGISTRY);
        MachineBeltAssemblyTest.check(compiled.buildable(), compiled.report().toString());
        var spans = MachineAssemblyDocument.belts(compiled.blueprint());
        MachineBeltAssemblyTest.check(spans.size() == 2 && spans.getFirst().second().getZ() == 1 && spans.getLast().first().getZ() == 0, "corner belongs to receiver");
        var occupied = new HashSet<>(); for (var span : spans) for (var at : span.cells()) MachineBeltAssemblyTest.check(occupied.add(at), "segments cannot overlap");
        var handoff = compiled.report().getAsJsonArray("item_handoffs").get(0).getAsJsonObject();
        MachineBeltAssemblyTest.check(handoff.get("declared_flow_compatible").getAsBoolean() && !handoff.get("power_connection_implied").getAsBoolean(), "item transfer is not a kinetic connection");
        var straight = JsonParser.parseString("[{\"type\":\"create:belt\",\"path\":[[0,0,0],[2,0,0],[20,0,0]]}]").getAsJsonArray();
        var split = MachineBeltRoutes.expand(straight, 128, 20);
        MachineBeltAssemblyTest.check(split.size() == 2 && split.get(0).getAsJsonObject().getAsJsonArray("second").get(0).getAsInt() == 18, "long leg leaves two cells for its last native segment");
        var opposed = JsonParser.parseString("""
                {"blocks":[],"assembly":{"installations":[
                {"type":"create:belt","first":[0,0,0],"second":[2,0,0],"flow":"first_to_second"},
                {"type":"create:belt","first":[3,0,0],"second":[5,0,0],"flow":"second_to_first"}]}}
                """).getAsJsonObject();
        MachineBeltAssemblyTest.check(!MachineBlueprintDocument.compile(opposed, MachineBeltAssemblyTest.REGISTRY).buildable(), "head-on receiving flow is not a valid handoff");
    }
}
