// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Bounds;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Cell;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Pos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Side;
import static org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout.position;

/** Six isolated 32-channel faces, with local glass terminal branches and one controller per network. */
final class MachineLayoutAeNetworks {
    static final String DENSE = "ae2:fluix_covered_dense_cable";
    record Leaf(String name, String blockId, Pos position, List<Side> sides) {}
    private static final Set<String> CHANNEL_DEVICES = Set.of("ae2:drive", "ae2:interface", "ae2:pattern_provider",
            "ae2:1k_crafting_storage", "ae2:terminal", "ae2:pattern_encoding_terminal", "ae2:crafting_terminal");
    private MachineLayoutAeNetworks() {}

    static void prepare(JsonObject design, MachineLayoutWork work, Bounds bounds, Map<String,Leaf> leaves) {
        if (!leaves.isEmpty() && !work.registry.itemExists(DENSE)) {
            work.fail("ae_dense_cable_unavailable", "The installed registry lacks the native dense-cable part required by the network planner."); return;
        }
        Map<String,List<String>> groups = new LinkedHashMap<>(); Map<String,String> parent = new LinkedHashMap<>();
        for (JsonElement e : design.getAsJsonArray("components")) {
            MachineLayoutRouting.checkpoint(); JsonObject c=e.getAsJsonObject();
            String name=c.get("name").getAsString();int count=c.get("count").getAsInt();List<String> names=new ArrayList<>();
            for(int i=0;i<count;i++){String expanded=count==1?name:name+"["+i+"]";names.add(expanded);parent.put(expanded,expanded);}groups.put(name,names);
        }
        for(JsonElement e:design.getAsJsonArray("connections")) {
            MachineLayoutRouting.checkpoint();JsonObject edge=e.getAsJsonObject();if(!edge.get("medium").getAsString().equals("ae_network"))continue;
            List<String> from=groups.get(edge.get("from").getAsString()),to=groups.get(edge.get("to").getAsString());
            if(from.size()!=to.size()&&from.size()!=1&&to.size()!=1)continue;
            for(int i=0;i<Math.max(from.size(),to.size());i++){
                String a=from.get(from.size()==1?0:i),b=to.get(to.size()==1?0:i);
                if(!leaves.containsKey(a)||!leaves.containsKey(b)){work.fail("ae_endpoint_adapter_unavailable","AE edge has no native grid port: "+a+" -> "+b);continue;}
                parent.put(root(parent,b),root(parent,a));
            }
        }
        parent.keySet().forEach(name->work.aeNetworks.put(name,root(parent,name)));
        Set<String> moduleOwners=new LinkedHashSet<>();
        work.modules.forEach(e->{var m=e.getAsJsonObject();if(m.get("module").getAsString().startsWith("ae2:"))moduleOwners.add(m.get("component").getAsString());});
        Map<String,Integer> demand=new LinkedHashMap<>();List<Cell> all=new ArrayList<>(work.cells.values());all.addAll(work.attachments);
        for(Cell c:all)if(c.owner().startsWith("component:")&&CHANNEL_DEVICES.contains(c.id()))demand.merge(c.owner().substring(10),1,Integer::sum);
        Map<String,List<Leaf>> networks=new LinkedHashMap<>();leaves.values().forEach(l->networks.computeIfAbsent(work.aeNetworks.get(l.name),ignored->new ArrayList<>()).add(l));
        JsonArray plans=new JsonArray();
        for(var entry:networks.entrySet()) {
            MachineLayoutRouting.checkpoint();String network=entry.getKey();List<Leaf> members=entry.getValue();
            List<Leaf> explicit=members.stream().filter(l->l.blockId.equals("ae2:controller")).toList();
            int count=members.stream().mapToInt(l->demand.getOrDefault(l.name,0)).sum();
            JsonObject report=new JsonObject();report.addProperty("network",network);report.addProperty("planned_required_channels",count);
            report.addProperty("terminal_branch_capacity",8);report.addProperty("channel_mode_verified",false);report.addProperty("backbone_item",DENSE);plans.add(report);
            if(explicit.size()>1){work.fail("ae_separate_controllers","Separate explicitly declared controllers cannot share one grid; request a connected controller multiblock.");continue;}
            boolean modules=members.stream().anyMatch(l->moduleOwners.contains(l.name));
            if(!modules&&explicit.isEmpty()){
                report.addProperty("backbone_capacity",8);
                if(count>8)work.fail("ae_controller_required","An ad-hoc network cannot serve "+count+" planned channels under standard AE rules; include a controller or cluster module.");
                continue;
            }
            List<List<Leaf>> bins=bins(members.stream().filter(l->!l.blockId.equals("ae2:controller")).toList(),demand);
            if(bins.size()>6){work.fail("ae_controller_face_capacity_exceeded","The indivisible cluster loads require "+bins.size()+" dense controller faces; one controller exposes six. A connected controller multiblock adapter is required.");continue;}
            Pos controller=explicit.isEmpty()?controllerSite(work,bounds,members):explicit.get(0).position;
            if(controller==null){work.fail("ae_controller_site_unresolved","No controller service bay with isolated cable exits fits inside the requested site.");continue;}
            for(Cell c:new ArrayList<>(work.cells.values())){
                String name=c.owner().startsWith("component:")?c.owner().substring(10):null;
                if(c.id().equals("ae2:controller")&&moduleOwners.contains(name)&&network.equals(work.aeNetworks.get(name)))work.cells.put(c.position(),new Cell(c.position(),DENSE,Map.of(),"center",c.owner()));
            }
            if(explicit.isEmpty())work.add(new Cell(controller,"ae2:controller",Map.of(),false,"ae_controller:"+network));
            JsonArray branches=new JsonArray();Set<Side> used=new LinkedHashSet<>();int branchIndex=0;
            for(List<Leaf> bin:bins){
                String owner="ae_branch:"+network+":"+branchIndex++;Set<String> names=new LinkedHashSet<>();bin.forEach(l->names.add(l.name));
                for(Cell c:new ArrayList<>(work.cells.values()))if(c.id().equals(DENSE)&&c.owner().startsWith("component:")&&names.contains(c.owner().substring(10)))work.cells.put(c.position(),new Cell(c.position(),c.id(),c.properties(),c.part(),owner));
                JsonObject branch=branch(work,bounds,controller,bin,members,demand,owner,used);
                if(branch==null){work.fail("ae_branch_route_unresolved","Cannot route an isolated 32-channel branch for "+network+"; enlarge the site or change its physical constraints.");break;}branches.add(branch);
            }
            report.addProperty("backbone_capacity",32*branches.size());report.addProperty("per_controller_face_capacity",32);
            report.add("controller_offset",position(controller));report.add("branches",branches);work.aeTopologyNetworks.add(network);
        }
        work.report.add("ae_channel_plans",plans);
    }

    private static List<List<Leaf>> bins(List<Leaf> members,Map<String,Integer> demand){
        List<Leaf> sorted=new ArrayList<>(members);sorted.sort(Comparator.<Leaf>comparingInt(l->demand.getOrDefault(l.name,0)).reversed());
        List<List<Leaf>> bins=new ArrayList<>();List<Integer> loads=new ArrayList<>();
        for(Leaf l:sorted){int n=demand.getOrDefault(l.name,0),chosen=-1;for(int i=0;i<loads.size();i++)if(loads.get(i)+n<=32){chosen=i;break;}
            if(chosen<0){chosen=bins.size();bins.add(new ArrayList<>());loads.add(0);}bins.get(chosen).add(l);loads.set(chosen,loads.get(chosen)+n);}
        return bins;
    }

    private static JsonObject branch(MachineLayoutWork work,Bounds bounds,Pos controller,List<Leaf> members,List<Leaf> networkMembers,Map<String,Integer> demand,String owner,Set<Side> used){
        String unresolved="no unused controller face";
        List<Side> faces=new ArrayList<>(List.of(Side.EAST,Side.WEST,Side.NORTH,Side.SOUTH,Side.UP,Side.DOWN));
        Pos average=new Pos((int)members.stream().mapToInt(l->l.position.x()).average().orElse(0),0,(int)members.stream().mapToInt(l->l.position.z()).average().orElse(0));
        faces.sort(Comparator.comparingInt(s->controller.step(s).distance(average)+(s.y!=0?4:0)));
        for(Side face:faces){
            MachineLayoutRouting.checkpoint();if(used.contains(face))continue;
            Map<Pos,Cell> attempt=new LinkedHashMap<>(work.cells);JsonArray paths=new JsonArray();boolean found=true;
            Set<Pos> clearance=new LinkedHashSet<>(work.clearance);
            // Reserve other face exits before routing the first branch, so it cannot wrap around
            // the controller and make every subsequent independent branch physically impossible.
            for(Side other:Side.values())if(other!=face){Pos exit=controller;for(int n=0;n<3;n++){
                exit=exit.step(other);clearance.add(exit);for(Side side:Side.values())clearance.add(exit.step(side));
            }}
            // Future leaves also need an uncontaminated entry; passing over their upward port
            // would otherwise make that port adjacent to the wrong controller-face branch.
            for(Leaf other:networkMembers)if(!members.contains(other)&&!other.blockId.equals("ae2:controller")){
                for(Side entry:other.sides){Pos exit=other.position;for(int n=0;n<3;n++){
                    exit=exit.step(entry);clearance.add(exit);for(Side side:Side.values())clearance.add(exit.step(side));
                }}
            }
            for(Leaf leaf:members){
                var route=MachineLayoutRouting.route(controller,leaf.position,List.of(face),leaf.sides,"ae_network",DENSE,owner,attempt,clearance,bounds);
                if(route==null){unresolved=owner+" via "+face+" cannot reach "+leaf.name+" at "+leaf.position+" from "+controller;found=false;break;}
                for(Cell c:route.cells())attempt.putIfAbsent(c.position(),c);
                if(attempt.size()+work.attachments.size()>SemanticMachineLayout.MAX_TARGETS){found=false;break;}
                JsonObject p=new JsonObject();p.addProperty("component",leaf.name);p.add("endpoint_offset",position(leaf.position));JsonArray cells=new JsonArray();route.cells().forEach(c->cells.add(position(c.position())));p.add("route",cells);paths.add(p);
            }
            if(!found)continue;
            work.cells.clear();work.cells.putAll(attempt);used.add(face);
            JsonObject result=new JsonObject();result.addProperty("face",face.label());result.addProperty("owner",owner);
            result.addProperty("planned_required_channels",members.stream().mapToInt(l->demand.getOrDefault(l.name,0)).sum());result.addProperty("capacity",32);result.add("paths",paths);return result;
        }
        work.fail("ae_branch_search_exhausted",unresolved);return null;
    }

    private static Pos controllerSite(MachineLayoutWork work,Bounds bounds,List<Leaf> leaves){
        int cx=(int)leaves.stream().mapToInt(l->l.position.x()).average().orElse(0),cz=(int)leaves.stream().mapToInt(l->l.position.z()).average().orElse(0);
        int examined=0,searchBudget=org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget.current().searchVisitedBudget();
        for(int radius=0;radius<=SemanticMachineLayout.MAX_RADIUS*2;radius++)for(int x=cx-radius;x<=cx+radius;x++)for(int z=cz-radius;z<=cz+radius;z++){
            MachineLayoutRouting.checkpoint();if(Math.max(Math.abs(x-cx),Math.abs(z-cz))!=radius)continue;
            if(++examined>searchBudget)return null;Pos candidate=new Pos(x,0,z);boolean free=true;
            for(int dx=-2;dx<=2&&free;dx++)for(int dz=-2;dz<=2&&free;dz++)for(int dy=-1;dy<=1;dy++){Pos p=new Pos(x+dx,dy,z+dz);if(!bounds.contains(p)||work.cells.containsKey(p)||work.clearance.contains(p)){free=false;break;}}
            if(free)return candidate;
        }return null;
    }

    static List<Pos> connectionPath(MachineLayoutWork work,Pos from,Pos to,String network){
        ArrayDeque<Pos> open=new ArrayDeque<>();Map<Pos,Pos> parent=new LinkedHashMap<>();open.add(from);parent.put(from,null);
        while(!open.isEmpty()){
            MachineLayoutRouting.checkpoint();Pos at=open.removeFirst();if(at.equals(to)){List<Pos> path=new ArrayList<>();for(Pos p=to;p!=null;p=parent.get(p))path.add(p);java.util.Collections.reverse(path);return path.subList(1,path.size()-1);}
            Cell source=work.cells.get(at);for(Side side:Side.values()){
                Pos next=at.step(side);Cell cell=work.cells.get(next);if(parent.containsKey(next)||!member(cell,network,work)||!connectable(source,side)||!connectable(cell,opposite(side)))continue;
                parent.put(next,at);open.addLast(next);
            }
        }return null;
    }
    private static boolean member(Cell c,String network,MachineLayoutWork work){if(c==null||!c.id().startsWith("ae2:"))return false;return c.owner().startsWith("ae_branch:"+network+":")||c.owner().equals("ae_controller:"+network)||c.owner().startsWith("component:")&&network.equals(work.aeNetworks.get(c.owner().substring(10)));}
    private static boolean connectable(Cell c,Side side){return c!=null&&!(c.id().equals("ae2:drive")&&side==Side.NORTH);}
    private static Side opposite(Side side){return switch(side){case EAST->Side.WEST;case WEST->Side.EAST;case SOUTH->Side.NORTH;case NORTH->Side.SOUTH;case UP->Side.DOWN;case DOWN->Side.UP;};}
    private static String root(Map<String,String> parent,String name){String root=name;while(!parent.get(root).equals(root)){MachineLayoutRouting.checkpoint();root=parent.get(root);}while(!name.equals(root)){String next=parent.get(name);parent.put(name,root);name=next;}return root;}
}
