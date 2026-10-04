package org.maiwithu.maicraft.core.task.physics;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.move.BoardStructureTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 为选胶角点或拉组装器寻找真实可见站位；明确无路可换位，效果未知时保留原导航回执停止。 */
final class AssemblyApproach {
    private StructureWorksiteSearch search;
    private Task movement;
    private boolean started,boarded;
    private BlockPos target;
    private String failure;
    private Map<String,Object> navigation=Map.of();
    private final List<Map<String,Object>> history=new ArrayList<>();
    boolean ready(LocalPlayer player,PhysicalAssemblyFrame frame,BlockPos offset,boolean honey,String call,long deadline) {
        return ready(player,frame,offset,eye->frame.aim(player,offset,eye,honey),call,deadline);
    }
    boolean ready(LocalPlayer player,PhysicalAssemblyFrame frame,BlockPos offset,Function<Vec3,Vec3> aimAt,String call,long deadline) {
        if(failure!=null)return false;
        if(target==null||!target.equals(offset)) {
            close();
            // 转向另一端点时归档前一端点的完整尝试，不能只留下最后一次走位。
            if(search!=null)history.add(Map.of("target_offset",List.of(target.getX(),target.getY(),target.getZ()),"search",search.diagnostics()));
            target=offset;search=null;boarded=false;
        }
        if(movement!=null) {
            if(!started) {movement.start(player);started=true;}
            TaskState state=movement.tick(player);if(!state.isTerminal())return false;
            var result=movement.result(state);movement=null;started=false;navigation=result.data();
            search.record(Map.of("success",result.success(),"message",result.message(),"navigation",navigation));
            String kind=String.valueOf(navigation.get("failure_type"));
            if(!result.success()&&!kind.equals("no_path")&&!kind.equals("planning_stall")) {failure=result.message();return false;}
        }
        if(aimAt.apply(player.getEyePosition())!=null)return true;
        var ctx=ClientRuntime.requireContext(player);
        if(search==null)search=new StructureWorksiteSearch(frame.world(offset),player.position(),player.blockInteractionRange(),player.getEyeHeight(Pose.STANDING));
        var space=JetpackRoute.observed(ctx);
        var candidate=search.advance(feet->{
            if(NavigationSafetyContext.forbidsBody(feet))return new StructureWorksiteSearch.Probe(null,false,false);
            var body=player.getDimensions(Pose.STANDING);
            var landing=TransportLanding.inspect(player.level(),player.level()::isLoaded,feet,body.width()+.16,body.height()+.08,NavigationSafetyContext.forbiddenBodyCells());
            if(landing.destination()==null)return new StructureWorksiteSearch.Probe(null,landing.unloaded(),landing.unknown());
            Vec3 at=landing.destination().landingPoint();
            // 配置频率或旋钮时也按真实小命中区选站位，不能只证明看见了整个方块。
            Vec3 aim=aimAt.apply(at.add(0,player.getEyeHeight(Pose.STANDING),0));
            if(aim==null||!space.clear(at,at))return new StructureWorksiteSearch.Probe(null,false,false);
            // 此处只保存规划瞄准点；真正发请求前仍检查实时射线，空气角点不会伪造成方块点击。
            return new StructureWorksiteSearch.Probe(new StructureWorksiteSearch.Site(landing.destination(),
                    new StructureEditTarget.Click(frame.storage(offset),Direction.UP,aim)),false,false);
        });
        if(candidate!=null) {
            // 气囊顶部与吊舱侧面高度相差较大时，先核验背包、燃料和落点；可飞行就受控换位，减少施工中的跌落。
            var mode=Math.abs(player.getY()-candidate.landing().landingPoint().y)>3
                    &&JetpackFlightSession.probe(ctx,candidate.landing().landingPoint(),NavigationSafetyContext.forbiddenBodyCells()).available()
                    ?TransportMode.JETPACK:TransportMode.AUTO;
            movement=new MoveToCompanionTask(player,MoveToTaskRecord.strictStance(call,deadline,candidate.landing().feet(),false,mode).withoutTerrainProbe());
        }
        else if(search.exhausted()) {
            if(frame.structure()!=null&&!boarded) {
                boarded=true;movement=new BoardStructureTask(player,new BoardStructureTaskRecord(call,deadline,frame.structure().id(),Vec3.atCenterOf(frame.storage(offset))));
            } else failure="附近站位与登船尝试没有建立可用选点视线";
        }
        return false;
    }
    String failure() { return failure; }
    boolean moving(){return movement!=null;}
    Map<String,Object> evidence() { return Map.of("previous_targets",List.copyOf(history),"search",search==null?Map.of():search.diagnostics(),
            "movement",movement==null?Map.of():movement.progress()); }
    void stop(LocalPlayer player,Task.StopReason why) { if(movement!=null)movement.stop(player,why); }
    void close() {
        if(movement!=null) {
            var result=movement.result(TaskState.CANCELLED);navigation=result.data();movement=null;
            if(search!=null)search.record(Map.of("success",false,"message",result.message(),"navigation",navigation));
        }
        started=false;
    }
}
