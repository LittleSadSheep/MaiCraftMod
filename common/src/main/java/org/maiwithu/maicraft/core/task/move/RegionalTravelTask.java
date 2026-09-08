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

/** One semantic goal; ground legs and continuous flight share observed surface-region selection. */
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
        goal=new RegionalGoal(player.position(),RegionalGoal.direction(r.direction,player.getYRot()),r.radius);
        terrain=new RegionalTerrain(player.position()); lastPosition=player.position();
    }
    protected TaskState onTick() {
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
        return (goal.matches(surface.point()) && surface.platform() ? 0 : 1000)
                + surface.point().distanceTo(position)-goal.progress(surface.point())*2;
    }
    private static boolean platformAt(RegionalTerrain.View view,Vec3 position) {
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
