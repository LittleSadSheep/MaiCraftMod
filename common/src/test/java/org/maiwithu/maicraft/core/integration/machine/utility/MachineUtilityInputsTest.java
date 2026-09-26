// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;
import com.google.gson.JsonPrimitive;

/** 验证契约无需世界、已安装模组或供电网络。 */
public final class MachineUtilityInputsTest {
    private static int checks;
    public static void main(String[] args) {
        // 没有外接需求时无需展开任何机器，保持旧的空文档查询语义。
        check(MachineUtilityInputs.parse(new JsonObject()).isEmpty(), "an absent utility declaration needs no blueprint");
        preservesConcreteRequirementsAcrossExplicitCompilation();
        historicalOffsetsDoNotDependOnTodaysPlanningBudget();
        rejectsImaginaryUnsafeOrMismatchedPorts();
        limitsSeparateNetworksAndChecksRequirements();
        itemInputsBindLocalReceivers();
        keepsOnsiteChoicesExplicit();
        System.out.println("MachineUtilityInputsTest: " + checks + " checks passed");
    }
    private static void historicalOffsetsDoNotDependOnTodaysPlanningBudget() {
        JsonArray rows = blueprint().getAsJsonArray("external_inputs");
        int oldOffset = MachinePlanningBudget.current().maxRadius() + 1;
        JsonArray at = new JsonArray(); at.add(oldOffset); at.add(0); at.add(0); rows.get(0).getAsJsonObject().add("offset", at);
        check(MachineUtilityInputs.parseStoredDeclarations(rows).getFirst().offset().getX() == oldOffset, "old catalog offset survives a smaller current planning radius");
        try { MachineUtilityInputs.parseDeclarations(rows); throw new AssertionError("current design accepted an over-budget offset"); }
        catch (IllegalArgumentException expected) { checks++; }
        at.set(0, new JsonPrimitive(30_000_001));
        try { MachineUtilityInputs.parseStoredDeclarations(rows); throw new AssertionError("catalog accepted unsupported world coordinates"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void preservesConcreteRequirementsAcrossExplicitCompilation() {
        JsonObject doc = blueprint();
        doc.getAsJsonArray("external_inputs").get(0).getAsJsonObject().addProperty("minimum_rpm", 32);
        var input = MachineUtilityInputs.parse(doc).getFirst();
        check(input.offset().getX() == 0 && input.face().getName().equals("west") && input.minimumRpm() == 32, "position, face and exact RPM requirement retained");
        var registry = new SemanticMachineLayout.Registry() {
            public boolean blockExists(String id) { return true; }
            public boolean itemExists(String id) { return true; }
            public boolean supportsState(String id, Map<String, String> properties) { return true; }
        };
        var compiled = MachineBlueprintDocument.compile(doc, registry);
        check(compiled.buildable(), "explicit port compiles without an internal connection graph");
        check(compiled.blueprint().getAsJsonArray("external_inputs").get(0).equals(input.json()), "canonical concrete input survives export");
        var recompiled = MachineBlueprintDocument.compile(compiled.blueprint(), registry);
        check(recompiled.report().get("external_inputs").equals(compiled.report().get("external_inputs")), "explicit recompilation keeps requirements immutable");
        check(!recompiled.report().get("utility_connection_verified").getAsBoolean(), "a declared input does not prove a connection");
        check(MachineUtilityInputs.parseDeclarations(compiled.blueprint().getAsJsonArray("external_inputs")).getFirst().equals(input), "bounded catalog declarations round-trip without retaining a whole blueprint");
    }
    private static void rejectsImaginaryUnsafeOrMismatchedPorts() {
        for (String block : new String[] {"create:creative_motor", "maicraft:stress_source", "minecraft:stone"}) {
            JsonObject doc = blueprint(); input(doc).addProperty("block_id", block); cell(doc).addProperty("block_id", block); rejects(doc);
        }
        JsonObject absent = blueprint(); input(absent).add("offset", JsonParser.parseString("[1,0,0]")); rejects(absent);
        JsonObject axis = blueprint(); cell(axis).getAsJsonObject("properties").addProperty("axis", "y"); rejects(axis);
        JsonObject unspecifiedAxis = blueprint(); cell(unspecifiedAxis).remove("properties"); rejects(unspecifiedAxis);
        JsonObject blocked = blueprint(); blocked.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[-1,0,0],\"block_id\":\"minecraft:stone\"}")); rejects(blocked);
        JsonObject enclosed = blueprint(); enclosed.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[-4,0,0],\"block_id\":\"minecraft:stone\"}")); rejects(enclosed);
        JsonObject reserved = blueprint(); reserved.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[-1,0,0],\"block_id\":\"minecraft:air\"}"));
        check(MachineUtilityInputs.parse(reserved).size() == 1, "explicit clearance is a valid reserved hookup cell");
        JsonObject part = blueprint(); cell(part).addProperty("part", "west"); rejects(part);
    }
    private static void limitsSeparateNetworksAndChecksRequirements() {
        JsonObject two = withInputs(2); rejects(two);
        reasons(two);
        check(MachineUtilityInputs.parse(two).size() == 2, "two explicitly justified independent networks accepted");
        JsonObject three = withInputs(3); reasons(three);
        check(MachineUtilityInputs.parse(three).size() == 3, "three is the hard per-medium limit");
        JsonObject four = withInputs(4); reasons(four); rejects(four);
        JsonObject duplicate = withInputs(2); reasons(duplicate); input(duplicate).addProperty("id", "drive1"); rejects(duplicate);
        JsonObject wrongResource = blueprint(); input(wrongResource).addProperty("resource", "minecraft:water"); rejects(wrongResource);
        JsonObject unknownUnits = blueprint(); input(unknownUnits).addProperty("minimum_capacity", 32); rejects(unknownUnits);
        for (String rpm : new String[] {"0", "-32", "1.5", "\"32\"", "null"}) {
            JsonObject invalid = blueprint(); input(invalid).add("minimum_rpm", JsonParser.parseString(rpm)); rejects(invalid);
        }
        JsonArray tooMany = new JsonArray(); for (int i = 0; i <= MachineUtilityInputs.MAX_INPUTS; i++) tooMany.add(input(blueprint()));
        try { MachineUtilityInputs.parseDeclarations(tooMany); throw new AssertionError("unbounded catalog declarations accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    /** 多道加工可各自收料，向下机械手与两格下方工件之间只需保留交接空间。 */
    private static void itemInputsBindLocalReceivers() {
        JsonObject doc = withInputs(16);
        for (int i = 0; i < 16; i++) {
            var port = doc.getAsJsonArray("external_inputs").get(i).getAsJsonObject();
            var block = doc.getAsJsonArray("blocks").get(i).getAsJsonObject();
            JsonArray at = JsonParser.parseString("[" + i + ",2,0]").getAsJsonArray();
            port.add("offset", at); block.add("offset", at.deepCopy());
            port.addProperty("medium", "items"); port.addProperty("block_id", "create:deployer"); port.addProperty("face", "down");
            port.addProperty("resource", "create:rose_quartz"); block.addProperty("block_id", "create:deployer");
            block.add("properties", JsonParser.parseString("{\"facing\":\"down\"}"));
        }
        doc.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[0,0,0],\"block_id\":\"create:depot\"}"));
        check(MachineUtilityInputs.parse(doc).size() == 16, "sixteen native item receivers need no central storage or straight exterior ray");
        MachineBlueprintDocument.validateWire(doc);
        var blocked = doc.deepCopy(); blocked.getAsJsonArray("blocks").add(JsonParser.parseString("{\"offset\":[0,1,0],\"block_id\":\"minecraft:stone\"}")); rejects(blocked);
        var imaginary = doc.deepCopy(); input(imaginary).addProperty("block_id", "create:mechanical_press"); cell(imaginary).addProperty("block_id", "create:mechanical_press"); rejects(imaginary);
        var shared = doc.deepCopy(); var second = shared.getAsJsonArray("external_inputs").get(1).getAsJsonObject();
        second.add("offset", input(shared).get("offset").deepCopy()); second.addProperty("resource", "minecraft:iron_ingot");
        check(MachineUtilityInputs.parse(shared).size() == 16, "one native inventory may receive two declared resources");
        second.addProperty("resource", "create:rose_quartz"); rejects(shared);
    }
    private static void keepsOnsiteChoicesExplicit() {
        JsonObject doc = blueprint();
        check(MachineUtilityInputs.supplyPreference(doc).equals("external"), "city utility supply is the default");
        doc.addProperty("supply_preference", "onsite"); rejects(doc);
        doc.addProperty("onsite_reason", "Remote emergency equipment requires an independent local source.");
        check(MachineUtilityInputs.parse(doc).size() == 1, "an explicitly justified local supply may coexist with an external backup input");
        JsonObject legacy = JsonParser.parseString("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"create:creative_motor\"}]}").getAsJsonObject();
        check(MachineUtilityInputs.parse(legacy).isEmpty(), "this boundary contract does not silently replace old explicitly authored blocks");
    }
    private static JsonObject withInputs(int count) {
        JsonObject result = blueprint(); result.add("blocks", new JsonArray()); result.add("external_inputs", new JsonArray());
        for (int i = 0; i < count; i++) {
            JsonObject port = input(blueprint()).deepCopy(), block = cell(blueprint()).deepCopy();
            port.addProperty("id", "drive" + i); JsonArray at = new JsonArray(); at.add(0); at.add(0); at.add(i * 2);
            port.add("offset", at); block.add("offset", at.deepCopy()); result.getAsJsonArray("external_inputs").add(port); result.getAsJsonArray("blocks").add(block);
        }
        return result;
    }
    private static void reasons(JsonObject doc) { doc.getAsJsonArray("external_inputs").forEach(e -> e.getAsJsonObject().addProperty("reason", "Independent rotation network with a different required direction.")); }
    private static JsonObject blueprint() { return JsonParser.parseString("""
            {"blocks":[{"offset":[0,0,0],"block_id":"create:shaft","properties":{"axis":"x"}}],
             "external_inputs":[{"id":"drive0","medium":"kinetic","offset":[0,0,0],"face":"west","block_id":"create:shaft"}]}
            """).getAsJsonObject(); }
    private static JsonObject input(JsonObject doc) { return doc.getAsJsonArray("external_inputs").get(0).getAsJsonObject(); }
    private static JsonObject cell(JsonObject doc) { return doc.getAsJsonArray("blocks").get(0).getAsJsonObject(); }
    private static void rejects(JsonObject doc) {
        try { MachineBlueprintDocument.validateWire(doc); throw new AssertionError("invalid external input accepted: " + doc); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
