// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonParser;

/** 同次审阅定位多个错误带段，保留原稿；修订反馈不以额外世界观察为前提。 */
public final class MachineDesignRejectionTest {
    public static void main(String[] args) {
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
}
