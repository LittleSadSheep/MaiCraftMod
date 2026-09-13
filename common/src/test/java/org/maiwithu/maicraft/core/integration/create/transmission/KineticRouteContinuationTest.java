// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;

public final class KineticRouteContinuationTest {
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var h=new InteractionWorldTestHarness()) {
            h.player.setUUID(UUID.randomUUID());var a=new BlockPos(1,1,1);var b=new BlockPos(5,1,1);var cell=new BlockPos(3,1,1);
            var source=new KineticRouteGeometry.Endpoint(a,Direction.Axis.X,List.of(Direction.EAST),"shaft");
            var target=new KineticRouteGeometry.Endpoint(b,Direction.Axis.X,List.of(Direction.WEST),"shaft");
            var plan=new KineticRouteGeometry.Plan("axial_shaft",source,Direction.EAST,target,Direction.WEST,
                    List.of(new KineticRouteGeometry.Placement(cell,"minecraft:stone",Map.of())),List.of(),Map.of("minecraft:stone",1));
            var request=new EconomicKineticTaskRecord("test",1000,"minecraft:overworld","city",a,Direction.EAST,"input",b,
                    Direction.WEST,"minecraft:stone",0,64,false,MaterialPolicy.INVENTORY_ONLY,List.of());
            KineticRouteContinuations.retain(h.player,request,plan,new JsonObject());
            check(KineticRouteContinuations.find(h.player,request).plan()==plan,"an unfinished request must retain its exact selected plan");
            check(KineticRouteContinuations.remaining(h.player,plan).equals(Map.of("minecraft:stone",1)),"an absent cell still needs its material");
            h.set(cell,Blocks.STONE.defaultBlockState());
            check(KineticRouteContinuations.remaining(h.player,plan).isEmpty(),"confirmed existing cells cannot demand or consume their materials again");
            check(KineticRouteContinuations.find(h.player,request)!=null,"matching construction must be resumable");
            h.set(cell,Blocks.COBBLESTONE.defaultBlockState());
            try {KineticRouteContinuations.find(h.player,request);throw new AssertionError("replaced cell accepted");}
            catch(IllegalArgumentException expected){check(expected.getMessage().contains("cell_changed"),"a changed partial route requires inspection");}
            KineticRouteContinuations.completed(h.player,b);check(KineticRouteContinuations.find(h.player,request)==null,"completion releases bounded ownership");
            var observed=new KineticNativeView.Observation(source,"create:shaft",16,true,"network-a");
            var evidence=KineticPowerEvidence.client(observed);
            check(KineticNativeReads.text(evidence,"provenance").equals("client_synchronized_native"),"client observations must not claim server proof");
            check(KineticPowerEvidence.sameNetwork(observed,evidence),"the same synchronized network can be checked without a return trip");
            check(!KineticPowerEvidence.sameNetwork(new KineticNativeView.Observation(source,"create:shaft",16,true,"network-b"),evidence),"source reassignment invalidates an old network proof");
            check(h.blockUses()==0&&h.itemUses()==0,"ledger reconciliation and evidence checks never mutate the world");
        }
        System.out.println("KineticRouteContinuationTest: exact partial ownership, remaining materials and labeled network evidence passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
