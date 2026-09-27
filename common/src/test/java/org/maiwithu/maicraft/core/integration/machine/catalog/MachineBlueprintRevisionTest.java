// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 只验证已完成改造的档案合并，不把图纸内容当成当前地图或原生动作证据。 */
public final class MachineBlueprintRevisionTest {
    public static void main(String[] args) {
        var original = document("""
                {"blocks":[
                  {"offset":[0,0,0],"block_id":"create:hand_crank"},
                  {"offset":[1,0,0],"block_id":"create:shaft"},
                  {"offset":[2,0,0],"block_id":"create:mechanical_press"},
                  {"offset":[2,-2,0],"block_id":"create:depot"}],
                 "expected_output":"create:iron_sheet",
                 "assembly":{"installations":[],"processing":[{"processor":[2,0,0],"surface":[2,-2,0]}]},
                 "external_inputs":[{"id":"drive","offset":[0,0,0]},{"id":"feed","offset":[2,-2,0]}]}
                """);
        var replace = document("""
                {"blocks":[{"offset":[0,0,0],"block_id":"create:water_wheel"},
                           {"offset":[1,0,0],"block_id":"minecraft:air"}],
                 "external_inputs":[{"id":"drive","offset":[0,1,0]}]}
                """);
        // 换动力并拆旧轴后，压机、置物台和加工关系仍在；空气也是明确设计目标，不能丢掉拆除意图。
        var merged = MachineBlueprintRevision.merge(original, replace);
        check(merged.getAsJsonArray("blocks").size() == 4
                && block(merged, "[1,0,0]").get("block_id").getAsString().equals("minecraft:air")
                && block(merged, "[2,0,0]").equals(block(original, "[2,0,0]")), "untouched machine blocks survive the patch");
        check(merged.get("expected_output").equals(original.get("expected_output"))
                && merged.getAsJsonObject("assembly").getAsJsonArray("processing").size() == 1
                && merged.getAsJsonArray("external_inputs").size() == 2, "unrelated processing and inputs survive");
        check(block(original,"[0,0,0]").get("block_id").getAsString().equals("create:hand_crank")
                && replace.getAsJsonArray("blocks").size() == 2, "input documents remain immutable");
        var removed = MachineBlueprintRevision.merge(merged, document("""
                {"blocks":[{"offset":[2,-2,0],"block_id":"minecraft:air"}]}
                """));
        check(removed.getAsJsonObject("assembly").getAsJsonArray("processing").isEmpty()
                && removed.getAsJsonArray("external_inputs").size() == 1, "removed surfaces retire their old processing relation and input");
        // 拆皮带中段即使没有点名两端，也不能让旧的原生整段安装留在后续设计里重新生成。
        var belt = document("""
                {"blocks":[{"offset":[0,0,0],"block_id":"create:shaft"},{"offset":[4,0,0],"block_id":"create:shaft"}],
                 "assembly":{"processing":[],"installations":[{"type":"create:belt","first":[0,0,0],"second":[4,0,0]}]}}
                """);
        var cut = MachineBlueprintRevision.merge(belt, document("""
                {"blocks":[{"offset":[2,0,0],"block_id":"minecraft:air"}]}
                """));
        check(cut.getAsJsonObject("assembly").getAsJsonArray("installations").isEmpty(), "cut span is not archived as an intact belt");
        // 同宿主两个面上的 AE 部件分别保留；改成整格空气时才清掉宿主内所有部件声明。
        var parts = document("""
                {"blocks":[{"offset":[0,0,0],"part":"north","item_id":"ae2:import_bus"},
                           {"offset":[0,0,0],"part":"south","item_id":"ae2:export_bus"}]}
                """);
        var partEdit = MachineBlueprintRevision.merge(parts, document("""
                {"blocks":[{"offset":[0,0,0],"part":"north","item_id":"ae2:export_bus"}]}
                """));
        check(partEdit.getAsJsonArray("blocks").size() == 2, "editing one part preserves the other face");
        check(MachineBlueprintRevision.merge(partEdit, document("""
                {"blocks":[{"offset":[0,0,0],"block_id":"minecraft:air"}]}
                """)).getAsJsonArray("blocks").size() == 1, "whole-cell removal replaces all part declarations");
        System.out.println("MachineBlueprintRevisionTest: partial modifications preserve untouched machine design");
    }
    private static JsonObject document(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject block(JsonObject value, String at) {
        return value.getAsJsonArray("blocks").asList().stream().map(row -> row.getAsJsonObject())
                .filter(row -> row.get("offset").toString().equals(at)).findFirst().orElseThrow();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
