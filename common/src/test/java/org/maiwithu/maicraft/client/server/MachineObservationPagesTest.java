// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import java.util.Map;
import org.maiwithu.maicraft.intent.SemanticResultView;

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
        check(pages.append(page, "request-one", 7), "native page accepted within its bounded report");
        JsonObject result = pages.report(1, 1, -1, 12);
        check(result.getAsJsonArray("pages").get(0).getAsJsonObject().get("tick").getAsInt() == 20
                && result.get("structural_anchor_tick").getAsInt() == 12, "structural and authoritative observation ticks stay distinct");
        check(!result.get("production_verified").getAsBoolean() && !result.get("flow_verified").getAsBoolean(),
                "stored inventory neither proves actual transfer nor sustained production");
        // 两个置物台即使持有同类工件，也必须各自绑定结构索引；对外去坐标不能抹掉这层归属。
        check(pages.append(page, "request-next-depot", 12), "second component page retained");
        result = pages.report(2, 2, -1, 12);
        JsonObject publicReport = new Gson().toJsonTree(SemanticResultView.data(Map.of("machine", result)))
                .getAsJsonObject().getAsJsonObject("machine");
        check(publicReport.getAsJsonArray("pages").get(0).getAsJsonObject().get("block_index").getAsInt() == 7
                && publicReport.getAsJsonArray("pages").get(1).getAsJsonObject().get("block_index").getAsInt() == 12,
                "public native pages retain their distinct structural block references");
        check(!page.has("block_index"), "binding a public copy cannot rewrite the original native receipt");
        var centerOnly = new MachineObservationPages();
        check(centerOnly.append(page, "empty-center", -1)
                && !centerOnly.report(1, 1, -1, 12).getAsJsonArray("pages").get(0).getAsJsonObject().has("block_index"),
                "an empty marked center cannot claim a nonexistent structural index");
        page.addProperty("large", "x".repeat(MachineObservationPages.MAX_CHARS));
        check(!pages.append(page, "request-two", 9), "oversized aggregate response is bounded");
        result = pages.report(2, 1, 9, 12);
        check(!result.get("complete").getAsBoolean() && result.get("next_component_index").getAsInt() == 9,
                "truncation retains actual first-page facts with an explicit continuation component");
        System.out.println("MachineObservationPagesTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
