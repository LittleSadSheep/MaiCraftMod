package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 用实际原版射线核对空气角点和遮挡，再核对建筑声明跨世界/存储坐标的转换。 */
public final class PhysicalAssemblyGeometryTest {
    public static void run() throws Exception {
        try(var h=new InteractionWorldTestHarness()) {
            h.position(new Vec3(.5,1,3.5));
            var frame=new PhysicalAssemblyFrame(h.level,null,BlockPos.ZERO);
            var air=new BlockPos(4,2,3);
            check(frame.aim(h.player,air,h.player.getEyePosition(),true)!=null,"蜂蜜胶应能选择原生最远射线空气格");
            check(frame.aim(h.player,air,h.player.getEyePosition(),false)==null,"强力胶不能把空气角点当方块面");
            check(frame.aim(h.player,new BlockPos(1,2,3),h.player.getEyePosition(),true)==null,"不能缩短原生 Alt 射线伪选近处空气");
            var delta=Vec3.atCenterOf(air).subtract(h.player.getEyePosition());
            h.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
            h.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
            check(frame.actualHit(h.player,air,true)!=null,"转头后仍须取得原生空气射线终点");
            h.set(new BlockPos(2,2,3),Blocks.STONE.defaultBlockState());
            check(frame.aim(h.player,air,h.player.getEyePosition(),true)==null&&frame.actualHit(h.player,air,true)==null,"遮挡必须同时拒绝规划和实际选点");
            check(frame.aim(h.player,new BlockPos(2,2,3),h.player.getEyePosition(),false)!=null,"可见实体表面未被选中");
            h.set(new BlockPos(2,2,3),Blocks.AIR.defaultBlockState());h.set(air,Blocks.TORCH.defaultBlockState());
            check(frame.aim(h.player,air,h.player.getEyePosition(),true)!=null,"无碰撞格的蜂蜜胶 Alt 选择应遵守原生碰撞射线");
            check(!frame.regionLoaded(new AABB(15,1,2,17,3,4)),"选区跨未加载区块不能被当作完整观察");
        }
        var cells=PhysicalStructureDesignStoreTest.patch(1,2,3,"minecraft:oak_planks");
        var mapped=AssemblyDesignMapping.assembled(cells,new BlockPos(100,50,200),new BlockPos(20480900,80,20480800),new BlockPos(20481000,128,20481000));
        check(AssemblyDesignMapping.point(mapped.get(0).getAsJsonObject()).equals(new BlockPos(1,4,3)),"组装转换不能假定原生锚点就是组装器或设计原点");
        var turned=AssemblyDesignMapping.movedToWorld(PhysicalStructureDesignStoreTest.patch(2,4,3,"minecraft:oak_planks"),
                new BlockPos(20481000,128,20481000),new BlockPos(20481001,132,20481003),new BlockPos(200,70,300),Rotation.CLOCKWISE_90);
        check(AssemblyDesignMapping.point(turned.get(0).getAsJsonObject()).equals(new BlockPos(200,70,301)),"拆回时未沿原生锚点执行整格转动");
        var project=JsonParser.parseString("[{\"block_id\":\"minecraft:lever\",\"x\":11,\"y\":20,\"z\":30,\"properties\":{\"facing\":\"north\",\"powered\":\"false\"},\"exact_properties\":[\"facing\"],\"final_properties\":[\"facing\"]}]").getAsJsonArray();
        var inherited=AssemblyDesignMapping.fromProject(project,new BlockPos(10,20,30));
        check(inherited.size()==1&&inherited.get(0).getAsJsonObject().getAsJsonObject("properties").size()==1,"未声明默认属性或空气被扩充成设计要求");
        check(AssemblyDesignMapping.point(inherited.get(0).getAsJsonObject()).equals(new BlockPos(1,0,0)),"建筑绝对坐标未转换到作者锚点");
    }
    private static void check(boolean ok,String why) {if(!ok)throw new AssertionError(why);}
}
