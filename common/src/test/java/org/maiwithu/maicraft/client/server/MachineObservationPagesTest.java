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
        repeatedEmptyViewsDoNotHideWorkpieces();
        System.out.println("MachineObservationPagesTest: passed");
    }
    private static void repeatedEmptyViewsDoNotHideWorkpieces() {
        // 模拟一个置物台的六面九槽：相同空槽资料压缩后，真实未完成件及其装配进度必须原样可读。
        var page = new JsonObject(); page.addProperty("schema", "maicraft.machine_snapshot.v1");
        page.addProperty("complete", false); page.addProperty("truncated", true); page.addProperty("tick", 42);
        page.addProperty("next_resource_offset", 54);
        var observations = new JsonArray(); var row = new JsonObject(); observations.add(row); page.add("observations", observations);
        var resources = new JsonArray(); var ports = new JsonArray(); row.add("resources", resources); row.add("ports", ports);
        var template = new JsonObject(); var identity = new JsonObject();
        identity.addProperty("kind", "items"); identity.addProperty("id", "minecraft:air"); identity.add("components", new JsonObject());
        template.add("identity", identity); template.addProperty("amount", 0); template.addProperty("capacity", 64);
        template.addProperty("resource_id", "items:minecraft:air#full-native-component-sensitive-key");
        template.addProperty("membership", "same-native-depot"); template.addProperty("provenance", "server_native");
        template.addProperty("views_may_overlap", true); template.addProperty("external_change_attribution", "unknown");
        for (String side : new String[]{"down", "up", "north", "south", "west", "east"}) {
            for (int slot = 0; slot < 9; slot++) {
                JsonObject empty = template.deepCopy(); empty.addProperty("side", side); empty.addProperty("slot", slot);
                empty.addProperty("storage_id", "same-native-depot/items/view:" + side + "/" + slot); resources.add(empty);
            }
            var port = new JsonObject(); port.addProperty("side", side); port.addProperty("api", "energy");
            port.addProperty("medium", "energy"); port.addProperty("status", "absent");
            port.addProperty("input", "unknown"); port.addProperty("output", "unknown"); ports.add(port);
        }
        JsonObject workpiece = template.deepCopy(); workpiece.addProperty("amount", 1);
        workpiece.getAsJsonObject("identity").addProperty("id", "create:incomplete_precision_mechanism");
        workpiece.getAsJsonObject("identity").getAsJsonObject("components").addProperty("create:sequenced_assembly", "step-two");
        resources.add(workpiece);
        var unknownPort = new JsonObject(); unknownPort.addProperty("status", "unknown"); unknownPort.addProperty("reason", "unreadable"); ports.add(unknownPort);
        JsonObject compressed = MachineObservationPresentation.compact(page), observed = compressed.getAsJsonArray("observations").get(0).getAsJsonObject();
        check(observed.getAsJsonArray("resources").size() == 1 && observed.getAsJsonArray("resources").get(0).equals(workpiece),
                "occupied workpiece keeps its exact native components and fields");
        check(observed.getAsJsonArray("empty_item_views").size() == 6, "different sided empty views remain separate");
        for (var empty : observed.getAsJsonArray("empty_item_views"))
            check(empty.getAsJsonObject().getAsJsonArray("views").size() == 9, "all individual empty slot references are retained");
        check(observed.getAsJsonArray("absent_ports").get(0).getAsJsonObject().getAsJsonArray("sides").size() == 6
                && observed.getAsJsonArray("ports").get(0).equals(unknownPort), "unknown ports never become known absent");
        check(compressed.get("tick").getAsInt() == 42 && !compressed.get("complete").getAsBoolean()
                && compressed.get("truncated").getAsBoolean() && compressed.get("next_resource_offset").getAsInt() == 54,
                "compaction cannot promote partial pages or change their native time and continuation");
        check(compressed.toString().length() < page.toString().length() / 2, "repeated empty views use less than half the report budget");
        check(resources.size() == 55 && !row.has("empty_item_views"), "original native receipt remains untouched");
        // 身份未知、带异常组件的空气或带物品身份的零计数都不能被折成已确认空槽。
        var uncertain = template.deepCopy(); uncertain.getAsJsonObject("identity").getAsJsonObject("components").addProperty("mod:custom", true);
        resources.add(uncertain); workpiece.addProperty("amount", 0);
        observed = MachineObservationPresentation.compact(page).getAsJsonArray("observations").get(0).getAsJsonObject();
        check(observed.getAsJsonArray("resources").size() == 2, "uncertain and typed zero-count resources stay explicit");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
