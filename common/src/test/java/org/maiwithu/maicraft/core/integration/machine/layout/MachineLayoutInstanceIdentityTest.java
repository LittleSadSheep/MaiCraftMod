// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 显示名即使与展开名称或内部编号相同，也不能改变设备身份、接线关系或生成格子。 */
public final class MachineLayoutInstanceIdentityTest {
    private static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String, String> properties) { return true; }
    };

    public static void main(String[] args) {
        var reference = compile(design("buffer"));
        for (String name : List.of("drive[0]", "drive[1]", "instance_0", "instance_1")) {
            JsonObject input = design(name);
            JsonObject original = input.deepCopy();
            var plan = compile(input);
            check(input.equals(original), "layout expansion must not rename the user's authored components");
            check(plan.blueprint().equals(reference.blueprint()),
                    "changing only a display name must preserve the exact generated blocks and parts");
            check(plan.report().get("logical_material_counts").equals(reference.report().get("logical_material_counts")),
                    "names must not change the physical material requirements");
            Map<String, JsonObject> instances = new LinkedHashMap<>();
            for (var value : plan.report().getAsJsonArray("components")) {
                JsonObject component = value.getAsJsonObject();
                String id = component.get("instance_id").getAsString();
                check(instances.put(id, component) == null, "every expanded device must retain a distinct identity");
            }
            check(instances.size() == 4, "one controller, two drives and one energy cell remain distinct");
            check(plan.report().getAsJsonArray("components").get(3).getAsJsonObject().get("name").getAsString().equals(name),
                    "the singleton's authored display name remains visible");
            var edges = plan.report().getAsJsonArray("connections");
            check(edges.size() == 3, "the grouped edge must connect both drives plus the separate energy cell");
            for (var value : edges) {
                JsonObject edge = value.getAsJsonObject();
                JsonObject from = instances.get(edge.get("from_instance_id").getAsString());
                JsonObject to = instances.get(edge.get("to_instance_id").getAsString());
                check(from != null && to != null && from != to, "connection references must identify their exact devices");
                check(edge.get("source_offset").equals(from.get("offset"))
                                && edge.get("destination_offset").equals(to.get("offset")),
                        "instance references must agree with the actual connected endpoint positions");
            }
        }
        System.out.println("MachineLayoutInstanceIdentityTest: display-name collisions preserve devices and exact layout");
    }

    private static SemanticMachineLayout.Result compile(JsonObject design) {
        var plan = SemanticMachineLayout.compile(design, 24, REGISTRY);
        check(plan.buildable(), "a valid named design must remain buildable: " + plan.report().get("unsupported"));
        return plan;
    }

    private static JsonObject design(String bufferName) {
        JsonObject result = JsonParser.parseString("""
                {"components":[
                  {"name":"control","block_id":"ae2:controller","count":1,"role":"network"},
                  {"name":"drive","block_id":"ae2:drive","count":2,"role":"storage"},
                  {"name":"buffer","block_id":"ae2:energy_cell","count":1,"role":"buffer"}],
                 "connections":[
                  {"from":"control","to":"drive","medium":"ae_network","purpose":"connect"},
                  {"from":"control","to":"buffer","medium":"ae_network","purpose":"connect"}]}
                """).getAsJsonObject();
        result.getAsJsonArray("components").get(2).getAsJsonObject().addProperty("name", bufferName);
        result.getAsJsonArray("connections").get(1).getAsJsonObject().addProperty("to", bufferName);
        return result;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
