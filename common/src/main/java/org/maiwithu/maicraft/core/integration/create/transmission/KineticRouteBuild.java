// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskRecord;

/** Pure plans become ordinary first-person construction, never direct state or creative inventory writes. */
final class KineticRouteBuild {
    private KineticRouteBuild() {}
    static TaskRecord task(LocalPlayer player,String id,long deadline,KineticRouteGeometry.Plan route,
                           EconomicKineticTaskRecord request,java.util.function.BooleanSupplier current) {
        var missing=new KineticRouteGeometry.Plan(route.family(),route.source(),route.sourceFace(),route.target(),route.targetFace(),
                route.placements().stream().filter(cell->!matches(player,cell)).toList(),route.chainLinks(),route.bom());
        var layout=MachineBlueprintDocument.compile(missing.blueprint(route.target().position()),MachineConstructionPlan.registry());
        if(!layout.buildable())throw new IllegalArgumentException("kinetic_native_blueprint_unavailable: "+layout.report());
        var plan=MachineConstructionPlan.compile(route.target().position(),layout,false,false);
        var blocks=plan.blockTask(id,deadline,true);
        var endpoints=List.of(route.source().position(),route.target().position());
        var preserved=new java.util.ArrayList<BlockPos>(endpoints);route.placements().forEach(cell->preserved.add(cell.position()));
        blocks.materialSupplyProtection(List.copyOf(preserved));
        blocks.executionGuards(endpoints,ignored -> current.getAsBoolean(),(actor,at) -> {
            if(!current.getAsBoolean()||!actor.level().isLoaded(at)||endpoints.contains(at))return false;
            if(route.placements().stream().anyMatch(value -> value.position().equals(at)))
                return emptyAndIsolated(actor,route,at);
            return true;
        },(actor,at)->{});
        return new SemanticBuildSupplyTaskRecord(id+"-materials",deadline,blocks,request.materialPolicy,request.allowedSources,
                request.allowHarm,request.protectedLabels,false);
    }
    static boolean emptyAndIsolated(LocalPlayer player,KineticRouteGeometry.Plan route,BlockPos at) {
        if(!player.level().isLoaded(at)||!player.level().getBlockState(at).isAir()
                ||!player.level().getBlockState(at).getFluidState().isEmpty())return false;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            if(dx==0&&dy==0&&dz==0)continue;
            var neighbor=at.offset(dx,dy,dz);
            if(neighbor.equals(route.source().position())||neighbor.equals(route.target().position())
                    ||route.placements().stream().anyMatch(cell->cell.position().equals(neighbor)))continue;
            if(!player.level().isLoaded(neighbor)||KineticNativeView.kinetic(player.level(),neighbor))return false;
        }
        return true;
    }
    static boolean matches(LocalPlayer player,KineticRouteGeometry.Plan route) {
        return route.placements().stream().allMatch(item->matches(player,item));
    }
    static boolean matches(LocalPlayer player,KineticRouteGeometry.Placement item) {
            if(!player.level().isLoaded(item.position()))return false;
            var desired=org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation.block(item.blockId(),null,null);
            if(player.level().getBlockState(item.position()).getBlock()!=desired.state().getBlock())return false;
            for(var property:item.properties().entrySet()) {
                var key=desired.state().getBlock().getStateDefinition().getProperty(property.getKey());
                if(key==null||!player.level().getBlockState(item.position()).getValue(key).toString().equalsIgnoreCase(property.getValue()))return false;
            }
        return true;
    }
}
