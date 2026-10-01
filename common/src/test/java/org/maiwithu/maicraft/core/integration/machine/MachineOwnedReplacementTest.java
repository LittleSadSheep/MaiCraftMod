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

/** 声明格的拆换无需先证明旧部件归属；范围外方块与明确保留条件仍单独核对。 */
public final class MachineOwnedReplacementTest {
    private static final BlockPos AT = new BlockPos(5,1,5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT,Blocks.BARREL.defaultBlockState());
            var owned = plan(true,true);
            owned.bindOwnedReplacements(h.level,(at,state)->at.equals(AT));
            var task = owned.blockTask("owned-replacement",1000,true);
            check(!blocked(h,task) && task.observedMachineEdit(AT,h.level.getBlockState(AT)), "owned declared replacement proceeds");
            check(!owned.modification(), "full new-build catalog semantics remain unchanged");
            var batch = new BuildTaskRecord("owned-batch",1000,task.targets,ReplaceMode.REPLACE_EMPTY,true,true,true,Map.of(),List.of(),true);
            task.copyExecutionContextTo(batch);
            check(!blocked(h,batch), "supply batches preserve owned replacement scope");
            // 相邻格已写进同一蓝图时可按授权拆换；再往外的未声明格不能继承这份许可。
            h.set(AT.east(),Blocks.BARREL.defaultBlockState());
            check(!blocked(h,task) && task.observedMachineEdit(AT.east(),h.level.getBlockState(AT.east())), "声明格不要求旧部件属于本任务");
            check(!task.observedMachineEdit(AT.east(2),Blocks.BARREL.defaultBlockState()), "未声明邻格不获得拆换许可");
            h.set(AT.east(),Blocks.AIR.defaultBlockState());
            h.set(AT,Blocks.CHEST.defaultBlockState());
            check(!blocked(h,task), "已声明范围按当前方块执行，不以旧快照作为新门控");
            for (boolean replace : new boolean[]{true,false}) {
                var denied = plan(replace,false); denied.bindOwnedReplacements(h.level,(at,state)->true);
                check(blocked(h,denied.blockTask("no-entity-permission",1000,true)), "ownership cannot override explicit replacement limits");
            }
            var unowned = plan(true,true); unowned.bindOwnedReplacements(h.level,(at,state)->false);
            check(!blocked(h,unowned.blockTask("unowned",1000,true)), "授权来自声明蓝图，不要求观测旧部件变成自有方块");
            check(h.blockUses()==0 && h.itemUses()==0,"binding and survey are read-only");
        }
        System.out.println("MachineOwnedReplacementTest: passed");
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
