package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskState;

// 先靠近并读取指定电梯楼层；有目标楼层时交给乘梯会话，只有实际乘梯并离开轿厢成功后才记录已到达位置。
final class ElevatorFloorTask extends AbstractCompanionTask<ElevatorFloorTaskRecord> {
    private final CreateElevatorBridge bridge=ElevatorInspection.bridge();
    private final ElevatorActions actions=new ElevatorActions();
    private MoveToCompanionTask approach;
    private boolean approached,requested;
    private CreateElevatorTravel travel;
    private TransportSession.Result result;
    private net.minecraft.world.phys.Vec3 lastPosition;
    ElevatorFloorTask(LocalPlayer player,ElevatorFloorTaskRecord record) { super(player,record); }
    protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        if(lastPosition==null || lastPosition.distanceToSqr(player.position())>.01) {
            lastPosition=player.position(); r.extendDeadlineTo(player.level().getGameTime()+600);
        }
        if(result!=null) {
            if(result.state()!=TransportSession.State.SUCCEEDED) {
                fail(result.code()+": "+result.detail(),result.uncertain() ? FailureType.UNKNOWN : FailureType.NO_PATH); return TaskState.FAILED;
            }
            var feet=player.blockPosition();
            r.verified=new InternalPositionReceipt.Position(feet.getX(),feet.getY(),feet.getZ(),player.level().dimension().location().toString());
            return TaskState.SUCCESS;
        }
        if(bridge==null) { fail("Create elevator API is unavailable",FailureType.UNSUPPORTED); return TaskState.FAILED; }
        var cabin=bridge.find(ctx,r.elevatorId);
        if(cabin==null) { fail("selected elevator is no longer loaded",FailureType.TARGET_LOST); return TaskState.FAILED; }
        if(approach!=null) {
            var state=runChild(approach);
            if(state==null) return TaskState.RUNNING;
            var moved=approach.result(state); approach=null;
            if(!moved.success()) { fail(moved.message(),FailureType.NO_PATH); return TaskState.FAILED; }
            approached=true; return TaskState.RUNNING;
        }
        if(r.approach && !approached) {
            boolean aboard=ElevatorInspection.supports(cabin,player) || bridge.recentSupport(cabin,player);
            if(aboard && !cabin.aligned(cabin.targetY())) return TaskState.RUNNING;
            if(!aboard && Math.hypot(player.getX()-cabin.column().x()-.5,player.getZ()-cabin.column().z()-.5)>4) {
                approach=new MoveToCompanionTask(player,new MoveToTaskRecord("elevator-observation-approach",player.level().getGameTime()+1200,
                        (double)cabin.column().x(),(double)player.blockPosition().getY(),(double)cabin.column().z(),null,false,false,
                        TransportMode.GROUND,false,false,4,1));
                return TaskState.RUNNING;
            }
            approached=true;
        }
        if(!actions.settle(ctx)) return TaskState.RUNNING;
        if(actions.failure!=null) { fail("elevator floor synchronization failed: "+actions.failure,FailureType.UNKNOWN); return TaskState.FAILED; }
        if(cabin.floors().isEmpty()) {
            if(requested) { fail("elevator returned no synchronized floor list",FailureType.UNKNOWN); return TaskState.FAILED; }
            requested=actions.submit(ctx,"create:elevator_floor_list",()->bridge.requestFloors(cabin),c->{
                var observed=bridge.find(c,r.elevatorId);
                return observed!=null && !observed.floors().isEmpty() ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
            });
            return TaskState.RUNNING;
        }
        if(r.floor==null) return TaskState.SUCCESS;
        if(!cabin.serves(r.floor)) { fail("selected floor is no longer served by this elevator",FailureType.TARGET_LOST); return TaskState.FAILED; }
        if(TransportRuntime.occupied() && !TransportRuntime.owns(this)) return TaskState.RUNNING;
        if(travel==null) travel=new CreateElevatorTravel(r.elevatorId,r.floor,NavigationSafetyContext.forbiddenBodyCells());
        if(!TransportRuntime.owns(this) && !TransportRuntime.acquire(this,"elevator",travel,ctx,value->result=value)) return TaskState.RUNNING;
        TransportRuntime.drive(this,ctx); return TaskState.RUNNING;
    }
    public void stop(LocalPlayer player,StopReason reason) {
        if(approach!=null) approach.stop(player,reason);
        TransportRuntime.cancel(this); super.stop(player,reason);
    }
    protected void cleanup() {
        if(approach!=null) { approach.result(TaskState.CANCELLED); approach=null; }
        TransportRuntime.cancel(this); super.cleanup();
    }
    protected String successMessage() { return r.floor==null ? "elevator floors synchronized; choose the destination" : "arrived and disembarked at the selected elevator floor"; }
    protected Map<String,Object> resultData() {
        return Map.of("elevator_id",r.elevatorId.toString(),"floors",ElevatorFloors.overview(player),
                "transport",travel==null ? Map.of() : travel.diagnostics());
    }
}
