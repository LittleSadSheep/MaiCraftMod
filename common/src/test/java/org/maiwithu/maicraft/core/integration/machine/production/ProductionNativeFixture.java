// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Real wire shapes with fixture-native observations, no Minecraft classes or invented game effects. */
final class ProductionNativeFixture {
    static final String DIMENSION = "minecraft:overworld";
    static final Point ANCHOR = new Point(100,64,100);
    static final JsonObject IRON = identity("minecraft:iron_ingot"), SHEET = identity("create:iron_sheet");
    static final String IRON_KEY = ProductionNativeJson.identityKey(IRON), SHEET_KEY = ProductionNativeJson.identityKey(SHEET);
    static JsonObject manifest() {
        JsonObject result = JsonParser.parseString(ProductionFixture.manifest().toString().replace("iron#fixture","minecraft:iron_ingot").replace("sheet#fixture","create:iron_sheet")).getAsJsonObject();
        JsonObject filter = result.getAsJsonArray("configurations").get(0).getAsJsonObject().getAsJsonObject("arguments");
        filter.remove("resource_id"); filter.addProperty("item_id","create:iron_sheet"); return result;
    }
    static ProductionNativeEvidence prepared(JsonObject input, long stock) {
        ProductionManifest manifest = ProductionManifest.parse(input); var evidence = new ProductionNativeEvidence(manifest);
        evidence.bind(DIMENSION,ANCHOR); evidence.supportedOperations(Set.of("machine.configure")); evidence.configured("finished_only",configured());
        for (Node node : manifest.nodes()) evidence.observeNode(node.id(),observation(node.offset(),null,0));
        for (Port port : manifest.ports()) evidence.observePort(port.id(),observation(port.offset(),port,port.node().equals("ae_supply") ? stock : 0));
        evidence.observeRecipe("press",recipe());
        for (Link link : manifest.links()) evidence.observeLink(link.id(),connection(link));
        return evidence;
    }
    static JsonObject observation(Point point, Port port, long stock) {
        JsonObject row = base(); row.add("position",absolute(point)); row.addProperty("provenance","server_native"); row.addProperty("block_id","create:mechanical_press");
        JsonArray ports = new JsonArray(), resources = new JsonArray(); row.add("ports",ports); row.add("resources",resources);
        if (port != null) {
            JsonObject p = new JsonObject(); p.addProperty("side",port.face()); p.addProperty("medium",port.medium()); p.addProperty("status","observed");
            p.addProperty("input","unknown"); p.addProperty("output","unknown"); ports.add(p);
            if (port.node().equals("ae_supply")) resources.add(stock(IRON,stock,port.face(),"shared-grid"));
        }
        JsonObject nativeState = new JsonObject(); JsonObject create = JsonParser.parseString("{\"getSpeed\":16,\"isOverStressed\":false,\"hasNetwork\":true,\"shaft_faces\":[\"north\",\"south\"]}").getAsJsonObject();
        nativeState.add("create",create); row.add("native",nativeState); row.add("unknown",new JsonArray()); return row;
    }
    static JsonObject recipe() {
        JsonObject row = base(); row.addProperty("schema","maicraft.machine_recipe.v1"); row.addProperty("recipe_id","create:pressing/iron_ingot");
        row.addProperty("provenance","server_recipe_manager_and_native_recipe_selection"); row.add("position",absolute(new Point(0,2,0)));
        row.addProperty("complete",true); row.addProperty("compatible",true); row.addProperty("compatibility","verified"); row.add("unknown",new JsonArray());
        JsonArray inputs = new JsonArray(); JsonObject input = new JsonObject(); input.addProperty("id","ingot"); input.addProperty("amount",1); input.addProperty("consumed",true);
        JsonArray alternatives = new JsonArray(); alternatives.add(resource(IRON)); input.add("alternatives",alternatives); inputs.add(input); row.add("inputs",inputs);
        JsonArray outputs = new JsonArray(); JsonObject output = new JsonObject(); output.add("resource",resource(SHEET)); output.addProperty("amount",1); output.addProperty("chance",1); outputs.add(output); row.add("outputs",outputs);
        row.add("minimum_power",JsonParser.parseString("[{\"resource\":{\"medium\":\"kinetic\",\"id\":\"rpm\"},\"amount\":1}]"));
        JsonArray conditions = new JsonArray(); JsonObject checks = new JsonObject();
        for (String id : Set.of("create:nonzero_rotation","create:not_overstressed","create:press_above_depot_or_belt")) { conditions.add(id); checks.addProperty(id,true); }
        row.add("conditions",conditions); row.add("condition_checks",checks); return row;
    }
    static JsonObject connection(Link link) {
        JsonObject row = base(); row.addProperty("schema","maicraft.connection_inspection.v1"); row.addProperty("status","verified"); row.addProperty("medium",link.resource().medium());
        row.addProperty("complete",true); row.addProperty("verified_connection",true); row.addProperty("operational",true); row.addProperty("resource_compatibility","verified");
        JsonArray edges = new JsonArray();
        for (int i=1;i<link.path().size();i++) { JsonObject edge = new JsonObject(); edge.add("from",absolute(link.path().get(i-1))); edge.add("to",absolute(link.path().get(i))); edge.addProperty("verified_connection",true); edges.add(edge); }
        row.add("edges",edges); JsonObject route = new JsonObject(); route.addProperty("sample_resource_id",Set.of("minecraft:iron_ingot",IRON_KEY).contains(link.resource().id()) ? IRON_KEY : SHEET_KEY); row.add("item_route",route); return row;
    }
    static JsonObject configured() {
        JsonObject row = base(); row.addProperty("status","applied"); row.addProperty("verified_configuration",true); row.addProperty("operation","machine.configure");
        row.addProperty("action","mekanism.sorter_filter"); row.add("position",absolute(new Point(1,0,0))); row.add("filter",SHEET.deepCopy()); return row;
    }
    static JsonObject stock(JsonObject identity, long amount, String side, String membership) {
        JsonObject row = new JsonObject(); row.add("identity",identity.deepCopy()); row.addProperty("resource_id",ProductionNativeJson.identityKey(identity)); row.addProperty("amount",amount);
        row.addProperty("storage_id",membership+"/view:"+side+"/0"); row.addProperty("membership",membership); row.addProperty("side",side); row.addProperty("provenance","server_native"); return row;
    }
    static JsonObject resource(JsonObject identity) { JsonObject row = new JsonObject(); row.addProperty("medium","items"); row.addProperty("id",ProductionNativeJson.identityKey(identity)); row.add("identity",identity.deepCopy()); return row; }
    static JsonObject identity(String id) { JsonObject row = new JsonObject(); row.addProperty("kind","items"); row.addProperty("id",id); row.add("components",new JsonObject()); return row; }
    static JsonObject base() { JsonObject row = new JsonObject(); row.addProperty("dimension",DIMENSION); row.addProperty("tick",100); return row; }
    static JsonArray absolute(Point p) { return new Point(ANCHOR.x()+p.x(),ANCHOR.y()+p.y(),ANCHOR.z()+p.z()).json(); }
}
