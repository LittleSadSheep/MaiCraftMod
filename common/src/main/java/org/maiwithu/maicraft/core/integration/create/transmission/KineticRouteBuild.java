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
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;

/** 纯计划会转换为普通第一人称施工，绝不直接修改方块状态或创造模式背包。 */
final class KineticRouteBuild {
    private KineticRouteBuild() {}
    static TaskRecord task(LocalPlayer player,String id,long deadline,KineticRouteGeometry.Plan route,
                           EconomicKineticTaskRecord request,BooleanSupplier current) {
        var missing=new KineticRouteGeometry.Plan(route.family(),route.source(),route.sourceFace(),route.target(),route.targetFace(),
                route.placements().stream().filter(cell->!matches(player,cell)).toList(),route.chainLinks(),route.bom());
        var layout=MachineBlueprintDocument.compile(missing.blueprint(route.target().position()),MachineConstructionPlan.registry());
        if(!layout.buildable())throw new IllegalArgumentException("kinetic_native_blueprint_unavailable: "+layout.report());
        var plan=MachineConstructionPlan.compile(route.target().position(),layout,false,false);
        var blocks=plan.blockTask(id,deadline,true);
        var endpoints=List.of(route.source().position(),route.target().position());
        var preserved=new ArrayList<BlockPos>(endpoints);route.placements().forEach(cell->preserved.add(cell.position()));
        blocks.materialSupplyProtection(List.copyOf(preserved));
        blocks.executionGuards(endpoints,ignored -> current.getAsBoolean(),(actor,at) -> {
            if(!current.getAsBoolean()||!actor.level().isLoaded(at)||endpoints.contains(at))return false;
            if(route.placements().stream().anyMatch(value -> value.position().equals(at)))
                return emptyForPlacement(actor,at);
            return true;
        },(actor,at)->{});
        return new SemanticBuildSupplyTaskRecord(id+"-materials",deadline,blocks,request.materialPolicy,request.allowedSources,
                request.allowHarm,request.protectedLabels,false);
    }
    static boolean emptyForPlacement(LocalPlayer player,BlockPos at) {
        // 只核对实际落点；不因斜邻格或相邻格存在其他动力部件拒绝已经准入的原生施工。
        return player.level().isLoaded(at)&&player.level().getBlockState(at).isAir()
                &&player.level().getBlockState(at).getFluidState().isEmpty();
    }
    static boolean matches(LocalPlayer player,KineticRouteGeometry.Plan route) {
        return route.placements().stream().allMatch(item->matches(player,item));
    }
    static boolean matches(LocalPlayer player,KineticRouteGeometry.Placement item) {
            if(!player.level().isLoaded(item.position()))return false;
            var desired=MachineInstallation.block(item.blockId(),null,null);
            if(player.level().getBlockState(item.position()).getBlock()!=desired.state().getBlock())return false;
            for(var property:item.properties().entrySet()) {
                var key=desired.state().getBlock().getStateDefinition().getProperty(property.getKey());
                if(key==null||!player.level().getBlockState(item.position()).getValue(key).toString().equalsIgnoreCase(property.getValue()))return false;
            }
        return true;
    }
}
