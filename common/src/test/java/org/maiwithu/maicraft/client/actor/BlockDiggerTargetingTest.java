package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.act.NativeBreakingTargeting;

/** 从高处挖底盘时优先真实顶面；原生可动把手遮挡与方块几何分别验证，不伪造连锁命中面。 */
public final class BlockDiggerTargetingTest {
    public static void run() throws Exception {
        try(var h=new InteractionWorldTestHarness()) {
            h.position(new Vec3(8.5,1,8.5));var pos=new BlockPos(10,1,8);h.set(pos,Blocks.OAK_PLANKS.defaultBlockState());
            var digger=new BlockDigger(h.player);digger.preferTopFace(true);
            check(digger.reachableHit(pos).getDirection()==Direction.UP,"优先顶面不能实际选择底面后命中侧面");
            check(h.mode.breakStarts==0,"站位和瞄准试算不能开始破坏");
        }
        var target=new BlockPos(10,1,8);var hit=new BlockHitResult(Vec3.atCenterOf(target),Direction.UP,target,false);
        var near=new NativeBreakingTargeting.HandleHit(target.west(),4);
        var same=new NativeBreakingTargeting.HandleHit(target,1);
        check(!NativeBreakingTargeting.matches(hit,9,List.of(near)),"前方油门把手没有阻止错误的木板瞄准");
        check(NativeBreakingTargeting.matches(hit,2,List.of(near)),"方块后方把手不应遮挡目标");
        check(NativeBreakingTargeting.matches(hit,9,List.of(near,same))&&NativeBreakingTargeting.matches(hit,9,List.of(same,near)),"最近命中必须独立于原生集合顺序");
        var side=new BlockHitResult(hit.getLocation(),Direction.WEST,target,false);
        check(!NativeBreakingTargeting.matches(side,9,List.of(same)),"原生把手的 UP 面不能冒充连锁挖掘需要的侧面");
        System.out.println("BlockDiggerTargetingTest: native handle occlusion and top-face selection passed");
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
