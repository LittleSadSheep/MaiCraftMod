// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** 禁用条件约束所有展开材料；测试使用虚拟注册表，不宣称实际建造过机器。 */
public final class MachineDesignConstraintsTest {
    private static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String, String> properties) { return true; }
    };
    public static void main(String[] args) {
        var design = parse("""
                {"components":[{"name":"input","block_id":"minecraft:chest","count":1,"role":"input"},
                  {"name":"output","block_id":"minecraft:chest","count":1,"role":"output"}],
                 "connections":[{"from":"input","to":"output","medium":"items","purpose":"transfer"}],
                 "constraints":{"forbidden_mods":["mekanism"]}}
                """);
        var result = SemanticMachineLayout.compile(design, 24, REGISTRY);
        check(!result.buildable() && result.blueprint().getAsJsonArray("blocks").isEmpty(), "implicit forbidden pipes cannot reach construction");
        check(result.report().getAsJsonArray("unsupported").toString().contains("forbidden_mod_dependency"), "precise forbidden dependency remains visible");
        var explicit = parse("""
                {"blocks":[{"offset":[0,0,0],"block_id":"other:machine"}],"constraints":{"forbidden_mods":["other"]}}
                """);
        check(!MachineBlueprintDocument.compile(explicit, REGISTRY).buildable(), "policy applies equally to other mods and explicit blueprints");
        explicit.getAsJsonObject("constraints").add("forbidden_mods", JsonParser.parseString("[\"mekanism\"]"));
        check(MachineBlueprintDocument.compile(explicit, REGISTRY).buildable(), "allowed authored blocks are preserved");
        try { MachineBlueprintDocument.validateBuildingWire(explicit); throw new AssertionError("ordinary building discarded machine constraints"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("build_machine"), "ordinary building directs machine constraints to the supported executor"); }
        var report = parse("{\"required_tools\":[\"mekanism:configurator\"],\"native_material_counts\":{\"minecraft:stone\":1}}");
        try { MachineDesignConstraints.verifyMaterials(explicit, report); throw new AssertionError("forbidden configuration tool accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("configurator"), "configuration tool is named"); }
        check(MachineDesignConstraints.read(parse("{\"forbidden_mods\":[\"create\",\"create\"]}")).equals(Set.of("create")), "constraints have stable set semantics");
        try { MachineDesignConstraints.read(parse("{\"forbidden_mods\":[\"Mek machines\"]}")); throw new AssertionError("informal mod name accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("namespace"), "caller must resolve installed namespaces"); }
    }
    private static JsonObject parse(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
