// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonParser;

public final class ChainConveyorConnectionPathTest {
    public static void main(String[] args) {
        String pair = "path:[{x:0,y:4,z:0},{x:24,y:4,z:0}]";
        parse("{system:'create',medium:'kinetic',link_kind:'chain_conveyor'," + pair + "}");
        rejects("{system:'create',medium:'kinetic'," + pair + "}");
        rejects("{system:'mekanism',medium:'energy',link_kind:'chain_conveyor'," + pair + "}");
        rejects("{system:'create',medium:'items',link_kind:'chain_conveyor'," + pair + "}");
        rejects("{system:'create',medium:'kinetic',link_kind:'guessed_shaft'," + pair + "}");
        rejects("{system:'create',medium:'kinetic',link_kind:'chain_conveyor',path:[{x:0,y:4,z:0},{x:24,y:4,z:0},{x:28,y:4,z:0}]}");
        rejects("{system:'create',medium:'kinetic',link_kind:'chain_conveyor',from_face:'east'," + pair + "}");
        rejects("{system:'create',medium:'kinetic',link_kind:'chain_conveyor',path:[{x:0,y:4,z:0},{x:0,y:4,z:0}]}");
        System.out.println("ChainConveyorConnectionPathTest: explicit nonlocal type admission preserves normal geometry constraints");
    }
    private static void parse(String json) { ConnectionPath.parse(JsonParser.parseString(json).getAsJsonObject()); }
    private static void rejects(String json) {
        try { parse(json); throw new AssertionError("accepted invalid nonlocal path: " + json); }
        catch (IllegalArgumentException expected) { }
    }
}
