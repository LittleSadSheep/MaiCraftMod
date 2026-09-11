// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class MachineObservationPagesTest {
    public static void main(String[] args) {
        var pages = new MachineObservationPages();
        JsonObject empty = pages.report(0, 0, -1, 12);
        check(!empty.get("complete").getAsBoolean() && empty.get("provenance").getAsString().equals("no_server_observation"),
                "installed capability without an actual reply cannot masquerade as native evidence");
        pages = new MachineObservationPages();
        JsonObject page = new JsonObject();
        page.addProperty("schema", "maicraft.machine_snapshot.v1");
        page.addProperty("complete", true);
        page.addProperty("tick", 20);
        var observations = new JsonArray();
        var nativeData = new JsonObject();
        nativeData.addProperty("resource_id", "component-sensitive-opaque-key");
        nativeData.addProperty("amount", 3);
        observations.add(nativeData);
        page.add("observations", observations);
        check(pages.append(page, "request-one"), "native page accepted within its bounded report");
        JsonObject result = pages.report(1, 1, -1, 12);
        check(result.getAsJsonArray("pages").get(0).getAsJsonObject().get("tick").getAsInt() == 20
                && result.get("structural_anchor_tick").getAsInt() == 12, "structural and authoritative observation ticks stay distinct");
        check(!result.get("production_verified").getAsBoolean() && !result.get("flow_verified").getAsBoolean(),
                "stored inventory neither proves actual transfer nor sustained production");
        page.addProperty("large", "x".repeat(MachineObservationPages.MAX_CHARS));
        check(!pages.append(page, "request-two"), "oversized aggregate response is bounded");
        result = pages.report(2, 1, 9, 12);
        check(!result.get("complete").getAsBoolean() && result.get("next_component_index").getAsInt() == 9,
                "truncation retains actual first-page facts with an explicit continuation component");
        System.out.println("MachineObservationPagesTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
