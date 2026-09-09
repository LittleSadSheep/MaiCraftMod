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

/**
 * 在 Create 加工设备的物品出口旁放一台 Mekanism 分拣机，并接物流管；分拣机背后取货、朝正面送出。
 * 布局成功后还登记“只提取指定成品”的过滤要求，真正写入过滤规则由装配任务完成。
 */
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
