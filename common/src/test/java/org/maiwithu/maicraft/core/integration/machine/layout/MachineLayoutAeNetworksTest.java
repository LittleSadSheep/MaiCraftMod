// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检查存储元件进入材料和装配要求，较大网络分到不同控制器面，分支不相邻串接；当前按常规频道规则测试生成格子，不读取服务器频道配置。
 */
public final class MachineLayoutAeNetworksTest {
    private static int checks;
    private static final SemanticMachineLayout.Registry REGISTRY=new SemanticMachineLayout.Registry(){
        public boolean blockExists(String id){return true;}
        public boolean itemExists(String id){return true;}
        public boolean supportsState(String id,Map<String,String> properties){return true;}
    };
    public static void main(String[] args){
        installsRealStorageCells();
        partitionsLargeNetwork(64);
        partitionsLargeNetwork(96);
        rejectsASeventhControllerFace();
        System.out.println("MachineLayoutAeNetworksTest: "+checks+" checks passed");
    }
    private static void installsRealStorageCells(){
        JsonObject d=graph();node(d,"store",true);var plan=compile(d,16);
        JsonObject content=plan.report().getAsJsonArray("initial_contents").get(0).getAsJsonObject();
        JsonElement driveOffset=content.get("offset");
        check(content.get("item_id").getAsString().equals("ae2:item_storage_cell_1k")&&content.get("count").getAsInt()==1,"default cluster contains a real registered 1k storage cell");
        check(targets(plan).stream().anyMatch(c->id(c).equals("ae2:drive")&&c.get("offset").equals(driveOffset)),"initial contents bind the actual translated drive");
        check(plan.report().getAsJsonObject("logical_material_counts").get("ae2:item_storage_cell_1k").getAsInt()==1,"storage-cell supply is included in material demand");
        d.getAsJsonArray("components").get(0).getAsJsonObject().add("module_options",JsonParser.parseString("{\"storage_tier\":\"64k\",\"storage_cells\":10}"));
        plan=compile(d,16);content=plan.report().getAsJsonArray("initial_contents").get(0).getAsJsonObject();
        check(content.get("item_id").getAsString().equals("ae2:item_storage_cell_64k")&&content.get("count").getAsInt()==10,"requested storage-cell tier and all ten physical drive slots are honored");
        d.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonObject("module_options").addProperty("storage_cells",11);
        check(!SemanticMachineLayout.compile(d,16,REGISTRY).buildable(),"eleven cells cannot be planned into a ten-slot drive");
    }
    // 按生成格子核对各分支最多三十二频道、不同面不共用或贴邻电缆，并保留终端所在的玻璃电缆宿主。
    private static void partitionsLargeNetwork(int wanted){
        JsonObject d=graph();int full=wanted/3,remainder=wanted%3,nodes=full+remainder;
        for(int i=0;i<nodes;i++){node(d,"cluster"+i,i<full);if(i>0)edge(d,"cluster"+(i-1),"cluster"+i);}
        var plan=compile(d,64);JsonObject network=plan.report().getAsJsonArray("ae_channel_plans").get(0).getAsJsonObject();
        check(network.get("planned_required_channels").getAsInt()==wanted,"all module devices and terminal parts contribute to the "+wanted+"-channel demand");
        check(network.getAsJsonArray("branches").size()>1,"large network uses multiple real controller faces");
        check(targets(plan).stream().filter(c->id(c).equals("ae2:controller")).count()==1,"connected clusters share exactly one controller instead of conflicting isolated controllers");
        List<Set<String>> branchCells=new ArrayList<>();Set<String> faces=new HashSet<>();
        for(JsonElement e:network.getAsJsonArray("branches")){
            JsonObject b=e.getAsJsonObject();check(b.get("planned_required_channels").getAsInt()<=32,"branch capacity never exceeds the native dense 32-channel limit");
            check(faces.add(b.get("face").getAsString()),"each dense branch occupies a distinct controller face");
            Set<String> cells=new HashSet<>();
            for(JsonElement p:b.getAsJsonArray("paths"))for(JsonElement c:p.getAsJsonObject().getAsJsonArray("route"))cells.add(c.toString());branchCells.add(cells);
        }
        for(int a=0;a<branchCells.size();a++)for(int b=a+1;b<branchCells.size();b++){
            Set<String> other=branchCells.get(b);
            for(String cell:branchCells.get(a)){
                check(!other.contains(cell),"different controller-face trees never reuse a cable cell");
                var p=MachineLayoutModules.from(JsonParser.parseString(cell).getAsJsonArray());
                for(var side:MachineLayoutRouting.Side.values())check(!other.contains(SemanticMachineLayout.position(p.step(side)).toString()),"different dense branches do not cross-connect outside the controller");
            }
        }
        Map<String,JsonObject> centers=new HashMap<>();
        for(JsonObject c:targets(plan))if(c.has("part")&&c.get("part").getAsString().equals("center"))centers.put(c.get("offset").toString(),c);
        for(JsonObject c:targets(plan))if(c.has("part")&&!c.get("part").getAsString().equals("center"))check(id(centers.get(c.get("offset").toString())).equals("ae2:fluix_glass_cable"),"terminal host remains a glass cable supporting side buses");
        check(plan.report().getAsJsonArray("connections").size()==nodes-1,"every semantic network edge is represented by an actual connected physical path");
    }
    private static void rejectsASeventhControllerFace(){
        JsonObject d=graph();for(int i=0;i<65;i++){node(d,"cluster"+i,true);if(i>0)edge(d,"cluster"+(i-1),"cluster"+i);}
        var plan=SemanticMachineLayout.compile(d,128,REGISTRY);
        check(!plan.buildable()&&plan.report().getAsJsonArray("unsupported").toString().contains("ae_controller_face_capacity_exceeded"),"a single controller's six real faces are not treated as unlimited channel capacity");
    }
    private static SemanticMachineLayout.Result compile(JsonObject d,int radius){var p=SemanticMachineLayout.compile(d,radius,REGISTRY);check(p.buildable(),"expected valid AE layout: "+p.report().get("unsupported"));return p;}
    private static JsonObject graph(){JsonObject d=new JsonObject();d.add("components",new JsonArray());d.add("connections",new JsonArray());return d;}
    private static void node(JsonObject d,String name,boolean module){JsonObject c=new JsonObject();c.addProperty("name",name);c.addProperty("block_id","ae2:drive");c.addProperty("count",1);c.addProperty("role","storage");if(module)c.addProperty("module","ae2:storage_cluster");d.getAsJsonArray("components").add(c);}
    private static void edge(JsonObject d,String from,String to){JsonObject e=new JsonObject();e.addProperty("from",from);e.addProperty("to",to);e.addProperty("medium","ae_network");e.addProperty("purpose","share storage network");d.getAsJsonArray("connections").add(e);}
    private static List<JsonObject> targets(SemanticMachineLayout.Result p){return p.blueprint().getAsJsonArray("blocks").asList().stream().map(JsonElement::getAsJsonObject).toList();}
    private static String id(JsonObject c){return c.get(c.has("block_id")?"block_id":"item_id").getAsString();}
    private static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
}
