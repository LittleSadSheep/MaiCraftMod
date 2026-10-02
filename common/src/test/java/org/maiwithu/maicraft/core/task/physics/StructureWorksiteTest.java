package org.maiwithu.maicraft.core.task.physics;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaterniond;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;

/** 重放甲板内侧看不见外侧面的失败，并验证地面替代站位与失败后继续搜索。 */
public final class StructureWorksiteTest {
    public static void run() {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        World world=new World();
        for(int x=0;x<=12;x++) for(int z=0;z<=12;z++) world.blocks.put(new BlockPos(x,0,z),Blocks.STONE.defaultBlockState());
        for(int x=3;x<=7;x++) for(int z=3;z<=7;z++) world.blocks.put(new BlockPos(x,1,z),Blocks.OAK_PLANKS.defaultBlockState());
        var pose=new StructurePose(Vec3.ZERO,0,0,0,1,Vec3.ZERO,new Vec3(1,1,1));
        var target=new BlockPos(8,1,5);
        var clicks=StructureEditTarget.targets(world,p->true,pose,target,true);
        var east=clicks.stream().filter(c->c.face()==Direction.EAST).findFirst().orElseThrow();
        Vec3 deckEye=new Vec3(6.58,3.27,5.5);
        check(world.ray(deckEye,east.world()).getDirection()==Direction.UP,"甲板内侧射线应先碰到顶面");
        check(StructureEditTarget.visible(deckEye,4.4,java.util.List.of(east),world::ray)==null,"被顶面挡住的外侧面不能回退为可见");
        Vec3 outsideEye=new Vec3(9.5,2.27,5.5);
        check(StructureEditTarget.visible(outsideEye,4.4,clicks,world::ray)!=null,"船侧地面眼点应能点击外侧面");
        check(StructureEditTarget.visible(outsideEye,1,clicks,world::ray)==null,"可见也不能越过原生触及距离");
        check(!StructureEditTarget.outsidePlacement(pose,target,new AABB(8.2,1,5.2,8.8,2.8,5.8)),"目标格内部不可作为放置站位");
        check(StructureEditTarget.outsidePlacement(pose,target,new AABB(9.2,1,5.2,9.8,2.8,5.8)),"外侧相邻站位不应被误拒绝");
        var top=StructureEditTarget.targets(world,p->true,pose,new BlockPos(5,2,5),true);
        check(StructureEditTarget.visible(deckEye,4.4,top,world::ray)!=null,"已经可见的顶部施工无需移位");
        check(StructureEditTarget.targets(world,p->false,pose,target,true).isEmpty(),"未加载的支撑不能生成施工面");
        // 船体转过九十度仍按存储坐标识别 EAST，不能拿世界方向替代原生命中面的方向。
        var q=new Quaterniond().rotationY(Math.PI/2);
        var turned=new StructurePose(new Vec3(20,4,30),q.x,q.y,q.z,q.w,Vec3.ZERO,new Vec3(1,1,1));
        var rotated=StructureEditTarget.targets(world,p->true,turned,target,true);
        check(StructureEditTarget.visible(turned.toWorld(outsideEye),4.4,rotated,
                (a,b)->world.ray(turned.toStorage(a),turned.toStorage(b)))!=null,"旋转后的世界眼点应命中同一本地支撑面");
        world.blocks.put(new BlockPos(9,2,5),Blocks.STONE.defaultBlockState());
        check(StructureEditTarget.visible(new Vec3(10.5,2.27,5.5),4.4,java.util.List.of(east),world::ray)==null,"中间障碍应使目标面不可见");
        world.blocks.remove(new BlockPos(9,2,5));
        var search=new StructureWorksiteSearch(east.world(),deckEye,4.4,1.27);
        var visited=new HashSet<BlockPos>();int attempts=0;
        while(!search.exhausted()) {
            int before=visited.size();
            var found=search.advance(p->{
                check(visited.add(p),"导航失败后不得反复选择同一候选格");
                var landing=TransportLanding.inspect(world,b->true,p,.6,1.8,LongSets.emptySet());
                if(landing.destination()==null) return new StructureWorksiteSearch.Probe(null,landing.unloaded(),landing.unknown());
                Vec3 feet=landing.destination().landingPoint();
                var body=new AABB(feet.x-.3,feet.y,feet.z-.3,feet.x+.3,feet.y+1.8,feet.z+.3);
                var click=StructureEditTarget.visible(feet.add(0,1.27,0),4.4,java.util.List.of(east),world::ray);
                return new StructureWorksiteSearch.Probe(click!=null&&StructureEditTarget.outsidePlacement(pose,target,body)
                        ?new StructureWorksiteSearch.Site(landing.destination(),click):null,false,false);
            });
            check(visited.size()-before<=24,"搜索不能在一刻里阻塞整个施工区");
            if(found!=null) { attempts++;search.record(Map.of("route_success",false,"feet",found.landing().feet().toString())); }
        }
        check(attempts>1,"首个站位走不到时还应找到其他有支撑且看得见的站位");
        check(((java.util.List<?>)search.diagnostics().get("attempts")).size()==attempts,"每次实际尝试均应完整保留");
        var missing=new StructureWorksiteSearch(Vec3.ZERO,Vec3.ZERO,1,1.27);
        missing.advance(p->new StructureWorksiteSearch.Probe(null,true,true));
        check(missing.diagnostics().get("unloaded_cells").equals(24)&&missing.diagnostics().get("unknown_cells").equals(24),"未知与未加载不能伪装成已证实无路");
    }
    private static final class World implements BlockGetter {
        final Map<BlockPos,BlockState> blocks=new HashMap<>();
        public BlockState getBlockState(BlockPos p){return blocks.getOrDefault(p,Blocks.AIR.defaultBlockState());}
        public BlockEntity getBlockEntity(BlockPos p){return null;}
        public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public int getHeight(){return 64;}public int getMinBuildHeight(){return -16;}
        BlockHitResult ray(Vec3 from,Vec3 to){return clip(new ClipContext(from,to,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,CollisionContext.empty()));}
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
