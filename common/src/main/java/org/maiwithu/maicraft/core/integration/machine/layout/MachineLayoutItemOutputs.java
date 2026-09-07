// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Bounds;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Cell;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Pos;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutRouting.Side;

/** Mekanism sorters read the inventory behind them and push filtered items out of their facing side. */
final class MachineLayoutItemOutputs {
    record Output(Cell sorter, MachineLayoutRouting.Route route) {}
    private MachineLayoutItemOutputs() {}
    static Output route(MachineLayoutWork work,Pos receiver,List<Side> sourceSides,Pos target,List<Side> targetSides,
                        String owner,Bounds bounds,String itemId) {
        if(!work.registry.itemExists(itemId)) { work.fail("output_filter_item_unavailable","Output filter item is not registered: "+itemId);return null; }
        List<Side> candidates=new ArrayList<>(sourceSides);
        candidates.sort(java.util.Comparator.comparingInt(s->receiver.step(s).distance(target)));
        for(Side facing:candidates){
            MachineLayoutRouting.checkpoint();Pos at=receiver.step(facing);
            if(!bounds.contains(at)||work.cells.containsKey(at)||work.clearance.contains(at))continue;
            Cell sorter=new Cell(at,"mekanism:logistical_sorter",Map.of("facing",facing.label()),false,"component:"+owner+":sorter");
            if(!work.registry.blockExists(sorter.id())||!work.registry.supportsState(sorter.id(),sorter.properties()))continue;
            Map<Pos,Cell> withSorter=new LinkedHashMap<>(work.cells);withSorter.put(at,sorter);
            var route=MachineLayoutRouting.route(at,target,List.of(facing),targetSides,"items","mekanism:basic_logistical_transporter",owner,withSorter,work.clearance,bounds);
            if(route==null)continue;
            // The constrained source side guarantees the first pipe is directly in front of the
            // sorter. Letting an unconstrained route turn on its first cell would not receive items.
            JsonObject filter=new JsonObject();filter.add("offset",SemanticMachineLayout.position(at));filter.addProperty("item_id",itemId);work.filters.add(filter);
            return new Output(sorter,route);
        }
        return null;
    }
}
