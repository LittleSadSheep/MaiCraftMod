package org.maiwithu.maicraft.core.task.move;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeAdapter;
import org.maiwithu.maicraft.core.integration.jetpack.RegionalFlightTarget;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.goal.RegionalGoal;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 朝指定方向寻找可站立区域：地面移动分段接近候选点，飞行则持续观察并选择着陆点。
 * 任务开始时还没有精确终点，只有观察到的支撑面满足目标条件才可以报告到达。
 */
public final class RegionalTravelTask extends AbstractCompanionTask<RegionalTravelTaskRecord> {
    private RegionalGoal goal;
    private RegionalTerrain terrain;
    private MoveToCompanionTask walk;
    private JetpackFlightSession flight;
    private TransportSession.Result flightResult;
    private final java.util.Set<BlockPos> attempted=new HashSet<>();
    private Vec3 lastPosition;
    private int legs;
    private boolean groundExhausted;
    public RegionalTravelTask(LocalPlayer player,RegionalTravelTaskRecord record) { super(player,record); }
    protected void onStart() {
        // 把“前/后/左/右”按出发时朝向固定为世界方向，避免寻路转头后连搜索目标也跟着旋转。
        goal=new RegionalGoal(player.position(),RegionalGoal.direction(r.direction,player.getYRot()),r.radius);
        terrain=new RegionalTerrain(player.position()); lastPosition=player.position();
    }
    protected TaskState onTick() {
        // 走地面时分段找候选，飞行时持续观察；先处理已经开始的那一段，不每刻重新选交通方式。
        var ctx=ClientRuntime.requireContext(player);
        if(player.position().distanceToSqr(lastPosition)>.04) {
            lastPosition=player.position(); r.extendDeadlineTo(player.level().getGameTime()+600);
        }
        if(flightResult!=null) {
            if(flightResult.state()==TransportSession.State.SUCCEEDED) return arrived();
            fail(flightResult.code()+": "+flightResult.detail(),flightResult.uncertain() ? FailureType.UNKNOWN : FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        boolean wantsFlight=walk==null && (r.mode==TransportMode.JETPACK || r.mode==TransportMode.AUTO
                && (goal.direction().y!=0 || groundExhausted) && JetpackNativeAdapter.inspect(ctx).controllable());
        // auto 在上下方向搜索或地面候选用尽时可转飞行；明确 jetpack 则直接尝试飞行控制。
        if(flight!=null || wantsFlight) {
            if(TransportRuntime.occupied() && !TransportRuntime.owns(this)) return TaskState.RUNNING;
            if(flight==null) flight=new JetpackFlightSession(new RegionalFlightTarget(goal),NavigationSafetyContext.forbiddenBodyCells());
            if(!TransportRuntime.owns(this) && !TransportRuntime.acquire(this,"jetpack",flight,ctx,value->flightResult=value)) return TaskState.RUNNING;
            TransportRuntime.drive(this,ctx); return TaskState.RUNNING;
        }
        if(player.position().distanceTo(goal.origin())>goal.radius()+2) {
            fail("regional discovery left its requested radius",FailureType.NO_PATH); return TaskState.FAILED;
        }
        var view=RegionalTerrain.observed(player);
        terrain.advance(view,4);
        if(player.onGround() && goal.matches(player.position()) && platformAt(view,player.position())) return arrived();
        if(walk!=null) {
            var state=runChild(walk);
            if(state==null) return TaskState.RUNNING;
            walk.result(state); walk=null; terrain=new RegionalTerrain(player.position());
            return TaskState.RUNNING;
        }
        if(!terrain.complete()) return TaskState.RUNNING;
        Vec3 next=terrain.surfaces().stream().filter(s->goal.contains(s.point()))
                .filter(s->s.point().distanceToSqr(player.position())>=9 && !attempted.contains(BlockPos.containing(s.point())))
                .sorted(Comparator.comparingDouble(s->groundScore(goal,player.position(),s)))
                .map(RegionalTerrain.Surface::point).findFirst().orElse(null);
        if(next==null || legs>=128) {
            // 地面最多尝试一百二十八段；auto 若有可用背包再换飞行，否则说明目前没有可尝试的已加载候选。
            if(r.mode==TransportMode.AUTO && JetpackNativeAdapter.inspect(ctx).controllable()) {
                groundExhausted=true; return TaskState.RUNNING;
            }
            fail("no untried loaded ground frontier toward the requested region; unseen terrain remains unknown",FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        attempted.add(BlockPos.containing(next)); legs++;
        BlockPos feet=org.maiwithu.maicraft.core.pathing.util.BlockHelper.playerFeet(player.level(),next.x,next.y,next.z);
        var record=new MoveToTaskRecord("regional-leg-"+legs,player.level().getGameTime()+1200,
                (double)feet.getX(),(double)feet.getY(),(double)feet.getZ(),null,r.mayAlterTerrain,false,TransportMode.GROUND,false,false,1,.5);
        walk=new MoveToCompanionTask(player,record); return TaskState.RUNNING;
    }
    static double groundScore(RegionalGoal goal,Vec3 position,RegionalTerrain.Surface surface) {
        // 已经符合最终区域且像平台的候选优先，否则在较近和沿目标方向前进之间打分。
        return (goal.matches(surface.point()) && surface.platform() ? 0 : 1000)
                + surface.point().distanceTo(position)-goal.progress(surface.point())*2;
    }
    private static boolean platformAt(RegionalTerrain.View view,Vec3 position) {
        // 到达时复查脚下和四邻的同高支撑，不能仅凭路径终点或旧采样宣布成功。
        int count=0;
        for(int[] offset:new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}}) {
            Vec3 floor=view.surfaceBelow(position.add(offset[0],.15,offset[1]),2);
            if(floor!=null && Math.abs(floor.y-position.y)<.1) count++;
        }
        return count>=3;
    }
    private TaskState arrived() {
        BlockPos feet=player.blockPosition();
        r.verified=new InternalPositionReceipt.Position(feet.getX(),feet.getY(),feet.getZ(),player.level().dimension().location().toString());
        return TaskState.SUCCESS;
    }
    public void stop(LocalPlayer player,StopReason reason) {
        // 当前对暂停和取消都结束交通控制；恢复后如何处理该结果，与通用 TransportNavigator 的暂停逻辑不同。
        if(walk!=null) walk.stop(player,reason);
        TransportRuntime.cancel(this); super.stop(player,reason);
    }
    protected void cleanup() {
        if(walk!=null) { walk.result(TaskState.CANCELLED); walk=null; }
        TransportRuntime.cancel(this); super.cleanup();
    }
    protected String successMessage() { return "arrived on an observed platform in the requested direction"; }
    protected Map<String,Object> resultData() {
        return Map.of("direction",r.direction,"ground_legs",legs,"terrain",terrain==null ? Map.of() : terrain.summary(),
                "flight",flight==null ? Map.of() : flight.diagnostics());
    }
}
