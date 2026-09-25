// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonParser;

/** 同次审阅定位多个错误带段，保留原稿；修订反馈不以额外世界观察为前提。 */
public final class MachineDesignRejectionTest {
    public static void main(String[] args) {
        correctionsStayAtTheFailingStage();
        var input = JsonParser.parseString("""
                {"blocks":[
                 {"offset":[0,0,0],"block_id":"create:shaft","properties":{"axis":"x"}},
                 {"offset":[0,0,2],"block_id":"create:shaft","properties":{"axis":"x"}}],
                 "assembly":{"installations":[
                 {"type":"create:belt","first":[0,0,0],"second":[4,0,0]},
                 {"type":"create:belt","first":[0,0,2],"second":[4,0,2]}]}}
                """).getAsJsonObject();
        var original = input.deepCopy();
        try { MachineBlueprintDocument.validateWire(input); throw new AssertionError("invalid axes accepted"); }
        catch (MachineDesignRejection rejected) {
            var details = rejected.details();
            if (details.get("diagnostic_count").getAsInt() != 2 || !details.getAsJsonArray("design_diagnostics").get(1).getAsJsonObject().get("path").getAsString().endsWith("[1]"))
                throw new AssertionError("both rejected spans need stable locations");
        }
        if (!original.equals(input)) throw new AssertionError("diagnostics changed the author blueprint");
    }

    private static void correctionsStayAtTheFailingStage() {
        // 蓝图轴向错误只改图，绑定抄错只改请求；只有已失效的场地才要求角色回到原工地重新勘测。
        String design = MachineDesignRejection.issue("invalid_belt_axis", "goal.parameters.blueprint.assembly")
                .get("next_action").getAsString();
        if (!design.contains("plan") || design.contains("perceive(")) throw new AssertionError("blueprint error must not trigger a survey");
        String binding = MachineDesignRejection.issue("machine_snapshot_target_mismatch", "goal.parameters.snapshot_id")
                .get("next_action").getAsString();
        if (!binding.contains("existing observation") || binding.contains("perceive(")) throw new AssertionError("binding typo must reuse the original observation");
        for (String code : new String[]{"machine_snapshot_missing", "machine_snapshot_expired", "machine_snapshot_changed", "machine_structure_incomplete"}) {
            String action = MachineDesignRejection.issue(code, "goal.parameters.snapshot_id").get("next_action").getAsString();
            if (!action.contains("perceive(view=construction_site)") || !action.contains("intended construction anchor"))
                throw new AssertionError("stale site needs a survey at its original anchor: " + code);
        }
    }
}
