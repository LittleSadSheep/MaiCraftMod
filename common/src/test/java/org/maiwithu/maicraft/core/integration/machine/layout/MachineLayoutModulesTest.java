// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tests native process geometry and module interface mapping, not just block inventory counts. */
public final class MachineLayoutModulesTest {
    private static int checks;
    private static final SemanticMachineLayout.Registry REGISTRY = new SemanticMachineLayout.Registry() {
        public boolean blockExists(String id) { return true; }
        public boolean itemExists(String id) { return true; }
        public boolean supportsState(String id, Map<String,String> properties) { return true; }
    };
    public static void main(String[] args) {
        pressHasReceiverAndRealBevelDrive();
        mixerHasBasinAndCogCoupling();
        aeModulesInstallPartLayersAndCraftingHardware();
        inductionMatrixHonorsShapeAndCommissioning();
        maximumNativeMatrixFitsLargeCompiler();
        rejectsWrongModuleCoreAndUnknownOptions();
        System.out.println("MachineLayoutModulesTest: " + checks + " checks passed");
    }

    private static void pressHasReceiverAndRealBevelDrive() {
        JsonObject design = design("create:mechanical_press", "create:press_station");
        addNode(design, "drive", "create:shaft"); addNode(design, "input", "minecraft:chest");
        edge(design, "drive", "station", "kinetic"); edge(design, "input", "station", "items");
        var plan = compile(design, 24);
        List<JsonObject> cells = targets(plan);
        JsonObject press = find(cells,"create:mechanical_press"), depot = find(cells,"create:depot");
        check(offset(press,0) == offset(depot,0) && offset(press,2) == offset(depot,2) && offset(press,1) == offset(depot,1)+2,
                "press operates exactly two blocks above its depot");
        check(press.getAsJsonObject("properties").get("facing").getAsString().equals("east"), "press exposes the horizontal shaft toward its drive");
        List<JsonObject> gears = cells.stream().filter(c -> id(c).equals("create:large_cogwheel")).toList();
        check(gears.size()==2, "press station contains both real bevel gears");
        JsonObject x = gears.stream().filter(c -> c.getAsJsonObject("properties").get("axis").getAsString().equals("x")).findFirst().orElseThrow();
        JsonObject y = gears.stream().filter(c -> c.getAsJsonObject("properties").get("axis").getAsString().equals("y")).findFirst().orElseThrow();
        check(distance(press,x)==1 && offset(press,1)==offset(x,1) && offset(press,2)==offset(x,2), "horizontal large cog directly receives the press shaft");
        check(Math.abs(offset(x,0)-offset(y,0))==1 && Math.abs(offset(x,1)-offset(y,1))==1 && offset(x,2)==offset(y,2), "perpendicular bevel cogs use Create's real diagonal X/Y coupling geometry");
        verifyMappedRoute(plan,"items","create:depot"); verifyMappedRoute(plan,"kinetic","create:large_cogwheel");
        checkGapReserved(plan, depot);
    }

    private static void mixerHasBasinAndCogCoupling() {
        JsonObject design = design("create:mechanical_mixer","create:mixer_station");
        addNode(design,"drive","create:shaft"); addNode(design,"water","create:fluid_tank");
        edge(design,"drive","station","kinetic"); edge(design,"water","station","fluids");
        var plan = compile(design,24); List<JsonObject> cells = targets(plan);
        JsonObject mixer=find(cells,"create:mechanical_mixer"), basin=find(cells,"create:basin"), cog=find(cells,"create:cogwheel");
        check(offset(mixer,1)==offset(basin,1)+2 && offset(mixer,0)==offset(basin,0) && offset(mixer,2)==offset(basin,2), "mixer's process basin is exactly two blocks below");
        check(distance(mixer,cog)==1 && offset(mixer,1)==offset(cog,1), "mixer couples through an adjacent parallel small cog, never a nonexistent mixer shaft");
        check(cog.getAsJsonObject("properties").get("axis").getAsString().equals("y"),"mixer and input cog rotate on the same axis");
        verifyMappedRoute(plan,"fluids","create:basin"); verifyMappedRoute(plan,"kinetic","create:cogwheel");
        checkGapReserved(plan,basin);
        check(plan.report().getAsJsonArray("obligations").toString().contains("unheated"),"recipe heat is not invented from a mixer layout");
    }

    private static void aeModulesInstallPartLayersAndCraftingHardware() {
        JsonObject storage = design("ae2:drive","ae2:storage_cluster");
        addNode(storage,"power","mekanism:basic_energy_cube"); edge(storage,"power","station","energy");
        var plan=compile(storage,24); List<JsonObject> cells=targets(plan);
        Map<String,Set<String>> parts=new HashMap<>();
        for(JsonObject c:cells) if(c.has("part")) parts.computeIfAbsent(c.get("offset").toString(),ignored->new HashSet<>()).add(c.get("part").getAsString());
        check(parts.values().stream().anyMatch(p->p.containsAll(Set.of("center","north"))),"terminal and cable are distinct native parts in one cable bus");
        verifyMappedRoute(plan,"energy","ae2:energy_acceptor");
        check(find(cells,"ae2:terminal")!=null && find(cells,"ae2:controller")!=null && find(cells,"ae2:interface")!=null,"storage cluster contains a real accessible terminal, controller and interface");
        var crafting=compile(design("ae2:pattern_provider","ae2:crafting_cluster"),24); cells=targets(crafting);
        check(distance(find(cells,"ae2:pattern_provider"),find(cells,"ae2:molecular_assembler"))==1,"provider physically touches its molecular assembler");
        check(find(cells,"ae2:1k_crafting_storage")!=null && find(cells,"ae2:pattern_encoding_terminal")!=null,"crafting cluster contains CPU storage and a pattern encoding terminal");
        check(crafting.report().getAsJsonArray("obligations").toString().contains("encode recipe-backed crafting patterns"),"layout cannot claim unencoded patterns are operational");
    }

    private static void inductionMatrixHonorsShapeAndCommissioning() {
        JsonObject design=design("mekanism:induction_casing","mekanism:induction_matrix");
        core(design).addProperty("module_tier","advanced");
        core(design).add("module_options",JsonParser.parseString("{\"width\":5,\"height\":4,\"depth\":6,\"cell_count\":8,\"provider_count\":2}"));
        addNode(design,"load","mekanism:energized_smelter"); edge(design,"station","load","energy");
        var plan=compile(design,24);
        JsonObject required=plan.report().getAsJsonArray("commissioning_requirements").get(0).getAsJsonObject();
        check(required.get("kind").getAsString().equals("mekanism_induction_matrix") && required.get("require_formed").getAsBoolean(),"matrix requires a formed native multiblock after construction");
        JsonArray min=required.getAsJsonArray("min_offset"),max=required.getAsJsonArray("max_offset");
        check(max.get(0).getAsInt()-min.get(0).getAsInt()==4 && max.get(1).getAsInt()-min.get(1).getAsInt()==3 && max.get(2).getAsInt()-min.get(2).getAsInt()==5,"commissioning uses exact translated module bounds");
        List<JsonObject> cells=targets(plan);
        check(cells.stream().filter(c->id(c).equals("mekanism:advanced_induction_cell")).count()==8,"requested storage-cell count is preserved");
        check(cells.stream().filter(c->id(c).equals("mekanism:advanced_induction_provider")).count()==2,"requested provider count is preserved");
        check(cells.stream().noneMatch(c->id(c).equals("minecraft:air")),"unused interior is verified clearance, never an air placement item");
        JsonObject seal=plan.report().getAsJsonArray("seal_after_cleanup").get(0).getAsJsonObject();
        for(JsonElement e:seal.getAsJsonArray("offsets"))check(cells.stream().anyMatch(c->c.get("offset").equals(e)&&id(c).equals("mekanism:induction_casing")),"temporary entrance is sealed with the original physical casing targets");
        check(plan.report().getAsJsonArray("clearance_cells").asList().contains(seal.get("outside_offset")),"sealing exit feet position is reserved clear before building");
        for(int y=1;y<=2;y++){
            JsonArray inside=new JsonArray();inside.add(min.get(0).getAsInt()+1);inside.add(min.get(1).getAsInt()+y);inside.add(min.get(2).getAsInt()+1);
            check(plan.report().getAsJsonArray("clearance_cells").asList().contains(inside),"partly filled matrix leaves a two-high interior approach to its temporary entrance");
        }
        JsonObject route=plan.report().getAsJsonArray("connections").get(0).getAsJsonObject();
        check(cells.stream().anyMatch(c->id(c).equals("mekanism:induction_port") && c.get("offset").equals(route.get("source_offset"))),"matrix output route attaches to a real configured output port");
    }

    private static void maximumNativeMatrixFitsLargeCompiler() {
        JsonObject design=design("mekanism:induction_casing","mekanism:induction_matrix");
        core(design).add("module_options",JsonParser.parseString("{\"width\":18,\"height\":18,\"depth\":18,\"cell_count\":4095,\"provider_count\":1}"));
        var plan=compile(design,32);
        check(plan.blueprint().getAsJsonArray("blocks").size()==18*18*18,"largest native matrix spans 5832 physical targets without the old 512-cell truncation");
    }

    private static void rejectsWrongModuleCoreAndUnknownOptions() {
        JsonObject wrong=design("minecraft:chest","create:press_station");
        check(!SemanticMachineLayout.compile(wrong,24,REGISTRY).buildable(),"module core identity must match its actual machine family");
        JsonObject arbitrary=design("create:mechanical_mixer","create:mixer_station");
        core(arbitrary).add("module_options",JsonParser.parseString("{\"width\":4}"));
        check(!SemanticMachineLayout.compile(arbitrary,24,REGISTRY).buildable(),"unsupported module options are not silently ignored");
        JsonObject script=design("mekanism:induction_casing","mekanism:induction_matrix");
        core(script).add("module_options",JsonParser.parseString("{\"clicks\":[]}"));
        check(!SemanticMachineLayout.compile(script,24,REGISTRY).buildable(),"module options cannot smuggle physical action scripts");
    }

    private static void verifyMappedRoute(SemanticMachineLayout.Result plan,String medium,String targetId) {
        JsonObject route=plan.report().getAsJsonArray("connections").asList().stream().map(JsonElement::getAsJsonObject).filter(c->c.get("medium").getAsString().equals(medium)).findFirst().orElseThrow();
        check(targets(plan).stream().anyMatch(c->id(c).equals(targetId)&&c.get("offset").equals(route.get("destination_offset"))),"semantic "+medium+" port maps to actual "+targetId);
        JsonArray path=route.getAsJsonArray("route");
        check(distance(path.get(path.size()-1).getAsJsonArray(),route.getAsJsonArray("destination_offset"))==1,"transport reaches the exact internal module port");
    }
    private static void checkGapReserved(SemanticMachineLayout.Result plan,JsonObject receiver) {
        JsonArray gap=receiver.getAsJsonArray("offset").deepCopy(); gap.set(1,new com.google.gson.JsonPrimitive(gap.get(1).getAsInt()+1));
        check(plan.report().getAsJsonArray("clearance_cells").asList().contains(gap),"processing head travel space is explicit required clearance");
        check(targets(plan).stream().noneMatch(c->c.get("offset").equals(gap)),"no transport or module block obstructs process head movement");
    }
    private static SemanticMachineLayout.Result compile(JsonObject design,int radius) { var p=SemanticMachineLayout.compile(design,radius,REGISTRY); check(p.buildable(),"module should compile: "+p.report().get("unsupported")); check(!p.report().get("production_verified").getAsBoolean(),"module geometry alone never proves production"); return p; }
    private static List<JsonObject> targets(SemanticMachineLayout.Result plan) { return plan.blueprint().getAsJsonArray("blocks").asList().stream().map(JsonElement::getAsJsonObject).toList(); }
    private static JsonObject find(List<JsonObject> cells,String id) { return cells.stream().filter(c->id(c).equals(id)).findFirst().orElseThrow(()->new AssertionError("missing "+id)); }
    private static String id(JsonObject c) { return c.get(c.has("block_id")?"block_id":"item_id").getAsString(); }
    private static int offset(JsonObject cell,int axis) { return cell.getAsJsonArray("offset").get(axis).getAsInt(); }
    private static int distance(JsonObject a,JsonObject b) { return distance(a.getAsJsonArray("offset"),b.getAsJsonArray("offset")); }
    private static int distance(JsonArray a,JsonArray b) { int d=0;for(int i=0;i<3;i++)d+=Math.abs(a.get(i).getAsInt()-b.get(i).getAsInt());return d; }
    private static JsonObject core(JsonObject design) { return design.getAsJsonArray("components").get(0).getAsJsonObject(); }
    private static JsonObject design(String block,String module) { JsonObject d=new JsonObject();d.add("components",new JsonArray());d.add("connections",new JsonArray());addNode(d,"station",block);core(d).addProperty("module",module);return d; }
    private static void addNode(JsonObject d,String name,String block) { JsonObject c=new JsonObject();c.addProperty("name",name);c.addProperty("block_id",block);c.addProperty("count",1);c.addProperty("role","process stage");d.getAsJsonArray("components").add(c); }
    private static void edge(JsonObject d,String from,String to,String medium) { JsonObject e=new JsonObject();e.addProperty("from",from);e.addProperty("to",to);e.addProperty("medium",medium);e.addProperty("purpose","supply processing resource");d.getAsJsonArray("connections").add(e); }
    private static void check(boolean value,String detail) { checks++;if(!value)throw new AssertionError(detail); }
}
