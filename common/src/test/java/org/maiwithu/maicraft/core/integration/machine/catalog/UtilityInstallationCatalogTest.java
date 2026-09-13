// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Ports must survive reconnect without inventing a producing line or a current connection. */
public final class UtilityInstallationCatalogTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("maicraft-utility-catalog-");
        var identity = new Identity("utility-test-world","player"); var anchor = new Position(8,64,8);
        var catalog = new MachineCatalog(directory,Runnable::run); catalog.bind(identity,"first");
        check(catalog.ready(),"catalog readiness");
        var inputs = MachineUtilityInputs.parseDeclarations(JsonParser.parseString("""
                [{"id":"main_drive","medium":"kinetic","offset":[0,1,0],"face":"down",
                  "block_id":"create:shaft","minimum_rpm":16}]
                """).getAsJsonArray());
        String id = catalog.registerInstallation(" Press "," minecraft:overworld ",anchor,inputs,1000);
        var planned = catalog.installation("minecraft:overworld",anchor).orElseThrow();
        check(planned.id().equals(id) && planned.label().equals("Press") && planned.builtAtMillis() == 0,"normalized identity and planned state");
        check(catalog.lines().isEmpty(),"unconnected construction cannot manufacture a production manifest");
        catalog.recordInstallationBuilt(" minecraft:overworld ",anchor,inputs,2000); catalog.saveAsync().join();
        var loaded = new MachineCatalog(directory,Runnable::run); loaded.bind(identity,"second"); check(loaded.ready(),"reload after dimension normalization");
        var built = loaded.installation("minecraft:overworld",anchor).orElseThrow();
        check(built.builtAtMillis() == 2000 && built.inputs().getFirst().face() == net.minecraft.core.Direction.DOWN,"port geometry and historical build survive reconnect");
        var view = built.json(false); var port = view.getAsJsonArray("external_inputs").get(0).getAsJsonObject();
        check(!view.get("current_connection_verified").getAsBoolean() && !view.get("machine_production_verified").getAsBoolean()
                && !view.get("operation_authorized").getAsBoolean() && !view.has("anchor") && !port.has("offset")
                && port.get("location_ref").getAsString().equals("Press/main_drive"),"historical metadata is neither current proof nor operation authority");
        check(built.json(true).getAsJsonArray("external_inputs").get(0).getAsJsonObject().getAsJsonObject("position").get("y").getAsInt() == 65,
                "relative port resolves against the frozen anchor");
        var changedJson = JsonParser.parseString(built.inputsJson()).getAsJsonArray();
        changedJson.get(0).getAsJsonObject().addProperty("minimum_rpm",32);
        var changed = MachineUtilityInputs.parseDeclarations(changedJson);
        loaded.registerInstallation("Press","minecraft:overworld",anchor,changed,3000);
        try { loaded.recordInstallationBuilt("minecraft:overworld",anchor,inputs,4000); throw new AssertionError("stale construction accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("changed"),"wrong rejection"); }
        check(loaded.installation("minecraft:overworld",anchor).orElseThrow().builtAtMillis() == 0,"revised input declarations reset construction evidence");
        var legacy = JsonParser.parseString(CatalogCodec.encode(new CatalogCodec.Snapshot(identity.key(),List.of(),List.of()))).getAsJsonObject();
        legacy.addProperty("version",1); legacy.remove("installations");
        check(CatalogCodec.decode(legacy.toString(),identity.key()).installations().isEmpty(),"existing catalog format remains readable");
        loaded.bind(new Identity("another-world","player"),"third"); check(loaded.ready() && loaded.installations().isEmpty(),"world boundaries");
        System.out.println("UtilityInstallationCatalogTest: durable anchored ports, normalized identity, staged evidence and legacy files passed");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
