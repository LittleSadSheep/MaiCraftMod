package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 半砖放置同时核验局部选点和原版放置规则，不能只看到命中同一侧面就立即提交。 */
public final class StructureSlabPlacementTest {
    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var h=new InteractionWorldTestHarness()) {
            var support=new BlockPos(1,1,1);var target=new BlockPos(2,1,1);
            h.set(support,Blocks.OAK_PLANKS.defaultBlockState());h.inventory.setItem(0,new ItemStack(Items.OAK_SLAB,4));
            var pose=StructurePose.copyOf(new Vector3d(10,20,30),new Quaterniond().rotateX(.2).rotateY(.5),new Vector3d(),new Vector3d(1));
            for(var half:new SlabType[]{SlabType.BOTTOM,SlabType.TOP}) {
                var options=StructureEditTarget.targets(h.level,p->true,pose,target,true,half);
                var click=options.stream().filter(c->c.support().equals(support)&&c.face()==Direction.EAST).findFirst().orElseThrow();
                Vec3 local=pose.toStorage(click.world());
                double expected=half==SlabType.BOTTOM?1.25:1.75;
                if(Math.abs(local.y-expected)>1e-6)throw new AssertionError("旋转结构应仍以本地方块半部选点");
                var hit=new BlockHitResult(local,click.face(),support,false);
                if(!StructureEditTask.slabHitMatches(h.player,Blocks.OAK_SLAB.defaultBlockState(),hit,half))
                    throw new AssertionError("所选位置必须经原版规则得到声明的半砖类型");
            }
            var tooHigh=new BlockHitResult(new Vec3(1.99999,1.75,1.5),Direction.EAST,support,false);
            if(StructureEditTask.slabHitMatches(h.player,Blocks.OAK_SLAB.defaultBlockState(),tooHigh,SlabType.BOTTOM))
                throw new AssertionError("镜头仍在同面高处时不能提前放下半砖");
            if(h.blockUses()!=0||h.inventory.getItem(0).getCount()!=4)throw new AssertionError("选点与检查不能提交操作或耗材");
        }
        System.out.println("StructureSlabPlacementTest: rotated target geometry and native slab state passed");
    }
}
