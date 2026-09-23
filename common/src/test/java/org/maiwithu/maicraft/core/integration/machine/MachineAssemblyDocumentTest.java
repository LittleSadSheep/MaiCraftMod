// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** 同一组件规则可处理不同产物，承载面与作用距离由适配证据决定，不使用产品工作站模板。 */
public final class MachineAssemblyDocumentTest {
    private static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String, String> properties) { return true; }
        public MachineProcessingCapabilities processing(String id, Map<String, String> properties) {
            boolean processor = id.equals("example:processor"), side = id.equals("example:side_processor");
            return new MachineProcessingCapabilities(processor || side, id.equals("create:belt") || id.equals("example:depot"),
                    id.equals("create:belt"), side ? new BlockPos(1, 0, 0) : new BlockPos(0, -2, 0),
                    side ? List.of() : List.of(new BlockPos(0, -1, 0)), processor ? Map.of("facing", "down") : Map.of(), null);
        }
    };
    public static void main(String[] args) {
        var input = sample();
        for (String output : List.of("example:precision_part", "example:assembled_track")) {
            input.addProperty("expected_output", output);
            var compiled = MachineBlueprintDocument.compile(input, REGISTRY);
            check(compiled.buildable(), "same surface contract works for either authored product: " + compiled.report());
            check(compiled.blueprint().getAsJsonArray("blocks").size() == 3, "compiler does not silently add a depot, sorter or pipe");
            check(!compiled.report().get("machine_production_verified").getAsBoolean(), "structural review does not invent production evidence");
            try { MachineConstructionPlan.compile(BlockPos.ZERO, compiled, false); throw new AssertionError("unbound native assembly reached ordinary placement"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("native_machine_assembly_executor"), "unsupported executor is rejected before world work"); }
        }
        var horizontal = sample(); horizontal.getAsJsonArray("blocks").get(2).getAsJsonObject().getAsJsonObject("properties").addProperty("facing", "north");
        check(!MachineBlueprintDocument.compile(horizontal, REGISTRY).buildable(), "processor orientation follows the native contract");
        var wrong = sample(); wrong.getAsJsonObject("assembly").getAsJsonArray("processing").get(0).getAsJsonObject().add("surface", JsonParser.parseString("[2,0,0]"));
        check(!MachineBlueprintDocument.compile(wrong, REGISTRY).buildable(), "a neighboring belt cell cannot stand in for the actual processing point");
        var side = JsonParser.parseString("""
                {"expected_output":"example:other_product","blocks":[
                {"offset":[0,0,0],"block_id":"example:side_processor"},{"offset":[1,0,0],"block_id":"example:depot"}],
                "assembly":{"processing":[{"processor":[0,0,0],"surface":[1,0,0]}]}}
                """).getAsJsonObject();
        check(MachineBlueprintDocument.compile(side, REGISTRY).buildable(), "other native offsets are not forced into a Create-only geometry");
        var forbidden = sample(); forbidden.add("constraints", JsonParser.parseString("{\"forbidden_mods\":[\"create\"]}"));
        check(!MachineBlueprintDocument.compile(forbidden, REGISTRY).buildable(), "native belt and connector obey the same namespace restrictions");
        var unknown = sample(); unknown.getAsJsonObject("assembly").getAsJsonArray("installations").get(0).getAsJsonObject().addProperty("type", "unknown:link");
        try { MachineBlueprintDocument.validateWire(unknown); throw new AssertionError("unknown action silently accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("unsupported_native_installation"), "missing primitive is explicit"); }
    }
    public static JsonObject sample() { return JsonParser.parseString("""
            {"expected_output":"example:product","blocks":[
              {"offset":[0,0,0],"block_id":"create:shaft","properties":{"axis":"z"}},
              {"offset":[4,0,0],"block_id":"create:shaft","properties":{"axis":"z"}},
              {"offset":[1,2,0],"block_id":"example:processor","properties":{"facing":"down"}}],
             "assembly":{"installations":[{"type":"create:belt","first":[0,0,0],"second":[4,0,0]}],
                         "processing":[{"processor":[1,2,0],"surface":[1,0,0]}]}}
            """).getAsJsonObject(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
