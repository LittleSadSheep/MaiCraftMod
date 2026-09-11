package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** Inspection precedes all movement; the selected structure is never inferred from its bounds alone. */
public final class VehicleDriveTask extends AbstractCompanionTask<VehicleDriveTaskRecord> {
    private MachineControlInspection.Observation observation;
    private VehicleControlPlan plan;
    private DriverStation station;
    private BoardStructureTask boarding;
    private VehicleDriveSession session;
    private TransportSession.Result outcome;
    public VehicleDriveTask(LocalPlayer player,VehicleDriveTaskRecord record) { super(player,record); }
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        if(observation==null) {
            try {
            observation=MachineControlInspection.structure(player,r.structureId);
            plan=VehicleControlPlan.compile(observation.circuit());
            if(!observation.complete() || !plan.usable()) {
                fail("vehicle controls are unresolved: "+plan.limitations(),FailureType.UNKNOWN); return TaskState.FAILED;
            }
            station=DriverStation.select(player,observation,plan);
            if(!station.seated(player) && observation.world(station.seat()).distanceTo(player.getEyePosition())>player.blockInteractionRange()-1)
                boarding=new BoardStructureTask(player,new BoardStructureTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),r.structureId,
                        net.minecraft.world.phys.Vec3.atCenterOf(station.seat())));
            } catch(IllegalArgumentException unavailable) {
                fail(unavailable.getMessage(),FailureType.NO_PATH); return TaskState.FAILED;
            }
        }
        if(boarding!=null) {
            TaskState state=runChild(boarding); if(state==null) return TaskState.RUNNING;
            var result=boarding.result(state); boarding=null;
            if(!result.success()) { fail(result.message(),FailureType.NO_PATH); return TaskState.FAILED; }
        }
        if(outcome!=null) {
            if(outcome.state()==TransportSession.State.SUCCEEDED) return TaskState.SUCCESS;
            fail(outcome.code()+": "+outcome.detail(),FailureType.UNKNOWN); return TaskState.FAILED;
        }
        if(session==null) session=new VehicleDriveSession(observation,plan,station,r.destination);
        if(!TransportRuntime.owns(this) && !TransportRuntime.acquire(this,"vehicle",session,ctx,result->outcome=result)) return TaskState.RUNNING;
        TransportRuntime.drive(this,ctx);
        return TaskState.RUNNING;
    }
    @Override protected void cleanup() {
        if(boarding!=null) { boarding.result(TaskState.CANCELLED); boarding=null; }
        TransportRuntime.cancel(this); super.cleanup();
    }
    @Override public void stop(LocalPlayer player,org.maiwithu.maicraft.task.Task.StopReason why) {
        if(boarding!=null) boarding.stop(player,why);
        TransportRuntime.cancel(this); super.stop(player,why);
    }
    @Override protected Map<String,Object> resultData() {
        var result=new java.util.LinkedHashMap<String,Object>();
        if(observation!=null) result.put("control_analysis",observation.report());
        if(plan!=null) result.put("control_limitations",plan.limitations());
        if(session!=null) result.put("vehicle",session.diagnostics());
        if(outcome!=null) result.put("uncertain",outcome.uncertain());
        return result;
    }
    @Override protected String successMessage() { return "confirmed driver seating, native control and stopped arrival"; }
    @Override public Map<String,Object> progress() { return session==null ? super.progress():session.diagnostics(); }
}
