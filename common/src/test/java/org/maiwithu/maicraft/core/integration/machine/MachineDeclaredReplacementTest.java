// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.build.BuildClearanceSurvey;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.ReplaceMode;

/** 蓝图声明格在替换许可下直接继承建造授权；未声明格与方块实体限制仍在各自门槛单独核对。 */
public final class MachineDeclaredReplacementTest {
    private static final BlockPos AT = new BlockPos(5,1,5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT,Blocks.BARREL.defaultBlockState());
            var authorized = plan(true,true);
            authorized.bindAutomaticModification(h.level);
            var task = authorized.blockTask("authorized-replacement",1000,true);
            // 声明格自动读取现场并继承替换许可；不再逐格要求归属或重复许可。
            check(!blocked(h,task) && task.observedMachineEdit(AT,h.level.getBlockState(AT)), "declared replacement proceeds with inherited authorization");
            var batch = new BuildTaskRecord("authorized-batch",1000,task.targets,ReplaceMode.REPLACE_EMPTY,true,true,true,Map.of(),List.of(),true);
            task.copyExecutionContextTo(batch);
            check(!blocked(h,batch), "supply batches preserve the inherited replacement scope");
            // 图纸未声明的格子拿不到拆改资格：勘测只覆盖声明目标，门槛在拆除前的原位核对。
            BlockPos outsider = AT.east(2); h.set(outsider,Blocks.BARREL.defaultBlockState());
            check(!task.observedMachineEdit(outsider,h.level.getBlockState(outsider)), "undeclared cells still need their own authorization");
            for (boolean replace : new boolean[]{true,false}) {
                var denied = plan(replace,false); denied.bindAutomaticModification(h.level);
                check(blocked(h,denied.blockTask("no-entity-permission",1000,true)), "inherited authorization cannot override explicit replacement limits");
            }
            check(h.blockUses()==0 && h.itemUses()==0,"binding and survey are read-only");
        }
        System.out.println("MachineDeclaredReplacementTest: passed");
    }
    private static MachineConstructionPlan plan(boolean replace, boolean entities) {
        var document = JsonParser.parseString("{\"schema_version\":1,\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:air\"},{\"offset\":[1,0,0],\"block_id\":\"minecraft:air\"}]}").getAsJsonObject();
        return MachineConstructionPlan.compile(AT,MachineBlueprintDocument.compile(document,MachineConstructionPlan.registry()),replace,entities);
    }
    private static boolean blocked(InteractionWorldTestHarness h, BuildTaskRecord task) {
        var survey = BuildClearanceSurvey.forPlan(h.player,task);
        for(int tick=0;tick<4096;tick++) if(survey.advance(128)) return survey.blocked();
        throw new AssertionError("clearance survey exceeded its bounded work");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
