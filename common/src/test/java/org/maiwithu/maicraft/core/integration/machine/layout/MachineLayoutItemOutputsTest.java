// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;

/**
 * 检查分拣机是否背对取货容器、正面接出管线、过滤绑定正确成品；连接指定的中间产物优先，未知产物则保持输出关闭。
 */
public final class MachineLayoutItemOutputsTest {
    private static int checks;
    private static final SemanticMachineLayout.Registry REGISTRY=new SemanticMachineLayout.Registry(){
        public boolean blockExists(String id){return true;}
        public boolean itemExists(String id){return true;}
        public boolean supportsState(String id,Map<String,String> state){return true;}
    };
    public static void main(String[] args){
        sorterFacesAwayFromProcessReceiver();
        edgeResourceOverridesFinalOutput();
        unknownOutputStaysInactive();
        System.out.println("MachineLayoutItemOutputsTest: "+checks+" checks passed");
    }
    private static void sorterFacesAwayFromProcessReceiver(){
        JsonObject d=design();d.addProperty("expected_output","create:iron_sheet");var p=compile(d);
        List<JsonObject> blocks=p.blueprint().getAsJsonArray("blocks").asList().stream().map(JsonElement::getAsJsonObject).toList();
        JsonObject sorter=blocks.stream().filter(c->c.has("block_id")&&c.get("block_id").getAsString().equals("mekanism:logistical_sorter")).findFirst().orElseThrow();
        JsonObject receiver=blocks.stream().filter(c->c.has("block_id")&&c.get("block_id").getAsString().equals("create:depot")).findFirst().orElseThrow();
        var source=MachineLayoutModules.from(receiver.getAsJsonArray("offset"));var at=MachineLayoutModules.from(sorter.getAsJsonArray("offset"));
        var facing=MachineLayoutRouting.Side.valueOf(sorter.getAsJsonObject("properties").get("facing").getAsString().toUpperCase(java.util.Locale.ROOT));
        check(source.step(facing).equals(at),"sorter reads the process receiver directly behind its facing");
        JsonArray route=p.report().getAsJsonArray("connections").get(0).getAsJsonObject().getAsJsonArray("route");
        check(MachineLayoutModules.from(route.get(0).getAsJsonArray()).equals(at),"sorter is the first physical output-route element");
        check(MachineLayoutModules.from(route.get(1).getAsJsonArray()).equals(at.step(facing)),"first transporter is directly in front of sorter, before any turn");
        JsonObject filter=p.report().getAsJsonArray("filters").get(0).getAsJsonObject();
        check(filter.get("offset").equals(sorter.get("offset"))&&filter.get("item_id").getAsString().equals("create:iron_sheet"),"native filter binds the correct sorter and finished item");
        check(p.report().getAsJsonArray("configurations").asList().stream().noneMatch(e->e.getAsJsonObject().get("mode").getAsString().equals("pull")),"sorter pushes filtered output and its receiving pipe never pulls unprocessed contents");
        check(p.report().getAsJsonObject("logical_material_counts").get("mekanism:logistical_sorter").getAsInt()==1,"sorter is included in physical material requirements");
    }
    private static void edgeResourceOverridesFinalOutput(){
        JsonObject d=design();d.addProperty("expected_output","create:iron_sheet");
        JsonObject edge=d.getAsJsonArray("connections").get(0).getAsJsonObject();edge.addProperty("item_id","create:copper_sheet");
        var p=compile(d);
        check(p.report().getAsJsonArray("filters").get(0).getAsJsonObject().get("item_id").getAsString().equals("create:copper_sheet"),"explicit intermediate item identity overrides the overall machine output");
        edge.addProperty("resource","create:iron_sheet");
        check(!SemanticMachineLayout.compile(d,24,REGISTRY).buildable(),"contradictory resource aliases cannot silently select a filter");
    }
    private static void unknownOutputStaysInactive(){
        var p=compile(design());
        check(p.report().getAsJsonArray("filters").isEmpty(),"no output identity is fabricated");
        check(p.report().getAsJsonArray("configurations").asList().stream().anyMatch(e->e.getAsJsonObject().get("mode").getAsString().equals("none")),"unidentified process output is physically disabled");
        check(p.report().getAsJsonArray("obligations").toString().contains("process_output_filter_required"),"disabled output is reported as pending commissioning work");
    }
    private static SemanticMachineLayout.Result compile(JsonObject d){var p=SemanticMachineLayout.compile(d,24,REGISTRY);check(p.buildable(),"expected output layout: "+p.report().get("unsupported"));return p;}
    private static JsonObject design(){return JsonParser.parseString("""
            {"components":[{"name":"press","block_id":"create:mechanical_press","count":1,"role":"stamp sheets","module":"create:press_station"},
             {"name":"output","block_id":"minecraft:chest","count":1,"role":"finished output buffer"}],
             "connections":[{"from":"press","to":"output","medium":"items","purpose":"collect finished sheets"}]}
            """).getAsJsonObject();}
    private static void check(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
}
