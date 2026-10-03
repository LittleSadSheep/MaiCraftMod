package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;

/** 重现轮座原格为空、上格实际生成的情况，确认原生效果同时保留未达成的设计，禁止机械补点扣料。 */
public final class BuildRedirectedPlacementTest {
    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var h=new InteractionWorldTestHarness()) {
            BlockPos at=new BlockPos(4,1,4);var air=Blocks.AIR.defaultBlockState();var stone=Blocks.STONE.defaultBlockState();
            var target=new BuildTaskRecord.Target(stone,Items.STONE,at,"偏移放置测试",null,null,null);
            h.player.getAbilities().instabuild=false;
            h.player.getInventory().setItem(0,new ItemStack(Items.STONE,4));
            var confirmation=new BuildPlacementConfirmation(target,List.of(),Map.of(at.asLong(),air),stone)
                    .trackMaterial(h.player).redirected(at.above(),stone,air);
            check(confirmation.materialVerdict(Verdict.NOT_APPLIED,3)==Verdict.PENDING,"已消耗材料被报成没生效，允许重复扣料");
            check(confirmation.observe(p->true,p->air,true)==Verdict.NOT_APPLIED,"世界不变的独立观察不应捏造方块");
            h.set(at.above(),stone);
            check(confirmation.observe(p->true,h.level::getBlockState,true)==Verdict.APPLIED&&confirmation.redirectedConfirmed(),"上移的原生落点没有确认");
            var diff=BuildFailureEvidence.diff(List.of(target),p->true,h.level::getBlockState);
            check(Boolean.FALSE.equals(diff.getFirst().get("matches")),"上移一格被冒充为完成原设计");
            var evidence=confirmation.diagnostics(p->true,h.level::getBlockState);
            check(evidence.containsKey("native_destination")&&evidence.containsKey("material_confirmation"),"回执没有保留真实落点与耗材");
        }
    }
    private static void check(boolean okay,String why) {if(!okay)throw new AssertionError(why);}
}
