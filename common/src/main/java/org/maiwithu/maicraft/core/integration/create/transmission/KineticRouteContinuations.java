// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Bounded session-local ownership receipts prevent a partial route from becoming permission to build a detour. */
final class KineticRouteContinuations {
    record Entry(Level world,java.util.UUID owner,EconomicKineticTaskRecord request,KineticRouteGeometry.Plan plan,
                 JsonObject costs,BlockEntity source,BlockEntity target,long expires) {}
    private static final Map<BlockPos,Entry> ENTRIES=new LinkedHashMap<>();
    private KineticRouteContinuations() {}
    static Entry find(LocalPlayer player,EconomicKineticTaskRecord request) {
        ENTRIES.entrySet().removeIf(row->row.getValue().world()!=player.level());
        Entry entry=ENTRIES.get(request.target);if(entry==null)return null;
        if(!entry.owner().equals(player.getUUID())||!sameRequest(entry.request(),request)
                ||player.level().getGameTime()>entry.expires())throw new IllegalArgumentException("kinetic_partial_route_requires_inspection");
        if(!player.level().isLoaded(entry.plan().source().position())||!player.level().isLoaded(request.target)
                ||player.level().getBlockEntity(entry.plan().source().position())!=entry.source()
                ||player.level().getBlockEntity(request.target)!=entry.target())throw new IllegalArgumentException("kinetic_partial_route_endpoint_changed");
        for(var cell:entry.plan().placements()) if(!player.level().isLoaded(cell.position())
                ||!player.level().getBlockState(cell.position()).isAir()&&!KineticRouteBuild.matches(player,cell))
            throw new IllegalArgumentException("kinetic_partial_route_cell_changed");
        return entry;
    }
    static void retain(LocalPlayer player,EconomicKineticTaskRecord request,KineticRouteGeometry.Plan plan,JsonObject costs) {
        if(ENTRIES.size()>=16&&!ENTRIES.containsKey(request.target))throw new IllegalArgumentException("kinetic_partial_route_receipt_capacity");
        ENTRIES.put(request.target.immutable(),new Entry(player.level(),player.getUUID(),request,plan,costs.deepCopy(),
                player.level().getBlockEntity(plan.source().position()),player.level().getBlockEntity(request.target),player.level().getGameTime()+24000));
    }
    static void completed(LocalPlayer player,BlockPos target) {
        var entry=ENTRIES.get(target);if(entry!=null&&entry.world()==player.level()&&entry.owner().equals(player.getUUID()))ENTRIES.remove(target);
    }
    static Map<String,Integer> remaining(LocalPlayer player,KineticRouteGeometry.Plan plan) {
        var result=new LinkedHashMap<String,Integer>();
        for(var cell:plan.placements()) {
            if(!player.level().isLoaded(cell.position()))throw new IllegalArgumentException("kinetic_route_unloaded_before_supply");
            if(!KineticRouteBuild.matches(player,cell))result.merge(
                    org.maiwithu.maicraft.core.integration.machine.MachinePlacementItems.itemId(cell.blockId(),cell.properties()),1,Integer::sum);
        }
        for(var link:plan.chainLinks()) {
            if(!player.level().isLoaded(link.from())||!player.level().isLoaded(link.to()))throw new IllegalArgumentException("kinetic_chain_unloaded_before_supply");
            boolean a=ChainConveyorBridge.isConveyor(player.level().getBlockEntity(link.from()));
            boolean b=ChainConveyorBridge.isConveyor(player.level().getBlockEntity(link.to()));
            boolean forward=a&&ChainConveyorBridge.connections(player.level(),link.from()).contains(link.to().subtract(link.from()));
            boolean backward=b&&ChainConveyorBridge.connections(player.level(),link.to()).contains(link.from().subtract(link.to()));
            if(forward!=backward)throw new IllegalArgumentException("kinetic_partial_chain_requires_native_inspection");
            if(!forward)result.merge("minecraft:chain",link.chains(),Integer::sum);
        }
        return Map.copyOf(result);
    }
    private static boolean sameRequest(EconomicKineticTaskRecord a,EconomicKineticTaskRecord b) {
        return java.util.Objects.equals(a.source,b.source)&&a.sourceFace==b.sourceFace&&a.targetFace==b.targetFace
                &&java.util.Objects.equals(a.targetBlockId,b.targetBlockId)&&a.minimumRpm==b.minimumRpm
                &&a.materialPolicy==b.materialPolicy&&a.allowedSources.equals(b.allowedSources)&&a.allowHarm==b.allowHarm
                &&a.protectedLabels.equals(b.protectedLabels)&&a.dimension.equals(b.dimension)&&a.sourceRadius==b.sourceRadius;
    }
}
