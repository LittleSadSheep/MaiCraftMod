// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;

/** 自卫或取料离场后只重开未施工的观察；已出手的安装与接链保持原生结算边界。 */
public final class KineticWorksiteReturnTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for(String phase:List.of("DISCOVER","PLAN","SOURCE","TARGET","EXISTING","MATERIALS","BUILD"))
            check(EconomicKineticTask.reinspectionAllowed(phase,false,false,false,0),"preconstruction stage may return: "+phase);
        for(String phase:List.of("LINKS","SOURCE_AFTER","TARGET_AFTER","DONE"))
            check(!EconomicKineticTask.reinspectionAllowed(phase,false,false,false,0),"post-effect stage must not restart: "+phase);
        check(!EconomicKineticTask.reinspectionAllowed("BUILD",true,false,false,0),"active construction cannot be replayed");
        check(!EconomicKineticTask.reinspectionAllowed("SOURCE",false,true,false,0),"built geometry retains its receipt");
        check(!EconomicKineticTask.reinspectionAllowed("SOURCE",false,false,true,0),"partial route admission remains strict");
        check(!EconomicKineticTask.reinspectionAllowed("SOURCE",false,false,false,1),"confirmed chain link cannot be replayed");
        try(var h=new InteractionWorldTestHarness()) {
            var progress=new KineticSupplyProgress();
            h.inventory.setItem(0,new ItemStack(Items.CHAIN,2)); check(progress.begin(h.inventory),"first stock admitted");
            h.inventory.setItem(0,new ItemStack(Items.CHAIN,3)); check(progress.begin(h.inventory),"second stock admitted");
            progress.interrupted(); check(progress.begin(h.inventory),"interrupted supply may resume from unchanged stock");
            h.inventory.setItem(0,new ItemStack(Items.CHAIN,2)); check(!progress.begin(h.inventory),"older completed material cycle remains rejected");
            var target=new BlockPos(5,1,1);
            var record=new EconomicKineticTaskRecord("return",1000,"minecraft:overworld","city",BlockPos.ZERO,null,
                    "machine",target,null,null,0,64,false,MaterialPolicy.ORDINARY,List.of());
            var task=new EconomicKineticTask(h.player,record);
            var current=EconomicKineticTask.class.getDeclaredMethod("bodyCurrent"); current.setAccessible(true);
            check((Boolean)current.invoke(task),"unloaded endpoint does not invalidate this player's world context");
            NavigationSafetyContext.withProtectedArea(List.of(target),List.of(),()->{
                try { check(!(Boolean)current.invoke(task),"return cannot bypass protected endpoint use"); }
                catch(ReflectiveOperationException failure){throw new AssertionError(failure);} return null;
            });
            task.stop(h.player,Task.StopReason.PREEMPTED);
            var resume=EconomicKineticTask.class.getDeclaredField("mayReturnToSite"); resume.setAccessible(true);
            check(resume.getBoolean(task),"native interruption records worksite return eligibility");
            check(h.blockUses()==0 && h.itemUses()==0,"recovery admission sends no block or item use");
        }
        System.out.println("KineticWorksiteReturnTest: passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
