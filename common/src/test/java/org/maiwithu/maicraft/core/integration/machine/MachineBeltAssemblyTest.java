// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** 验证只声明运输路线也能生成端轴；显式设备、部件和模组禁令保持约束力。 */
public final class MachineBeltAssemblyTest {
    static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String, String> properties) { return true; }
    };
    public static void main(String[] args) {
        JsonObject input = sample(); var result = MachineBlueprintDocument.compile(input, REGISTRY);
        check(result.buildable() && input.getAsJsonArray("blocks").isEmpty(), "derivation must not mutate the author document");
        check(result.blueprint().getAsJsonArray("blocks").size() == 2, "a belt derives only its necessary endpoint shafts");
        check(MachineAssemblyDocument.properties(result.blueprint().getAsJsonArray("blocks").get(0).getAsJsonObject()).get("axis").equals("z"), "X travel requires Z axles");
        check(MachineBlueprintDocument.compile(result.blueprint(), REGISTRY).blueprint().equals(result.blueprint()), "recompilation does not duplicate generated shafts");
        // 中段加一根轴就应公开两个真实轴端；端口引用不能随第二次编译变化，也不能提前声称已供电。
        JsonObject withPulley = sample();
        var installation = withPulley.getAsJsonObject("assembly").getAsJsonArray("installations").get(0).getAsJsonObject();
        installation.add("pulleys", JsonParser.parseString("[[2,0,0]]")); installation.addProperty("flow", "first_to_second");
        var powered = MachineBlueprintDocument.compile(withPulley, REGISTRY);
        var ports = powered.report().getAsJsonArray("power_ports");
        check(ports.size() == 6 && powered.blueprint().getAsJsonArray("blocks").size() == 3, "two endpoints and one intermediate pulley");
        check(ports.get(0).getAsJsonObject().get("powered").isJsonNull(), "a planned port is not an energized observation");
        check(ports.equals(MachineBlueprintDocument.compile(powered.blueprint(), REGISTRY).report().get("power_ports")), "stable port references");
        installation.add("pulleys", JsonParser.parseString("[[2,0,1]]")); rejects(withPulley, "belt_pulley_must_be_unique_and_on_span");
        input.add("constraints", JsonParser.parseString("{\"forbidden_mods\":[\"create\"]}"));
        check(!MachineBlueprintDocument.compile(input, REGISTRY).buildable(), "derived materials obey namespace restrictions");
        input = sample(); input.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[0,0,0],\"block_id\":\"minecraft:chest\"}"));
        rejects(input, "belt_endpoint_conflicts_with_authored_block");
        input = sample(); input.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[0,0,0],\"block_id\":\"create:shaft\",\"properties\":{\"axis\":\"x\"}}"));
        rejects(input, "belt_axes_or_slope_incompatible");
        input = sample(); input.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[0,0,0],\"item_id\":\"ae2:cable\",\"part\":\"center\"}"));
        rejects(input, "native_installation_overlap");
    }
    static JsonObject sample() { return JsonParser.parseString("""
            {"blocks":[],"assembly":{"installations":[{"type":"create:belt","first":[0,0,0],"second":[4,0,0]}]}}
            """).getAsJsonObject(); }
    static void rejects(JsonObject input, String code) {
        try { MachineBlueprintDocument.validateWire(input); throw new AssertionError("accepted " + code); }
        catch (IllegalArgumentException rejected) { check(rejected.getMessage().contains(code), rejected.getMessage()); }
    }
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
