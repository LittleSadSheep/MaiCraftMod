package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.physics.PhysicalStructureApproach;
import org.maiwithu.maicraft.task.TaskState;
import java.util.LinkedHashMap;
import org.maiwithu.maicraft.task.Task;

/** 所有移动前必须先完成检查；不能仅凭外边界推断目标结构。 */
public final class VehicleDriveTask extends AbstractCompanionTask<VehicleDriveTaskRecord> {
    private MachineControlInspection.Observation observation;
    private VehicleControlPlan plan;
    private DriverStation station;
    private final PhysicalStructureApproach approach=new PhysicalStructureApproach();
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
            } catch(IllegalArgumentException unavailable) {
                fail(unavailable.getMessage(),FailureType.NO_PATH); return TaskState.FAILED;
            }
        }
        if(outcome!=null) {
            if(outcome.state()==TransportSession.State.SUCCEEDED) return TaskState.SUCCESS;
            fail(outcome.code()+": "+outcome.detail(),FailureType.UNKNOWN); return TaskState.FAILED;
        }
        if(session==null) {
            // 开始驾驶租约前先完成走位，座位在触及距离内却被甲板遮挡时仍需换到可见面。
            if(!station.seated(player)&&!approach.ready(player,r.structureId,station.seat(),r.getToolCallId(),r.getDeadlineGameTime())) {
                if(approach.failure()!=null){fail(approach.failure(),FailureType.NO_PATH);return TaskState.FAILED;}
                return TaskState.RUNNING;
            }
            session=new VehicleDriveSession(observation,plan,station,r.destination);
        }
        if(!TransportRuntime.owns(this) && !TransportRuntime.acquire(this,"vehicle",session,ctx,result->outcome=result)) return TaskState.RUNNING;
        TransportRuntime.drive(this,ctx);
        return TaskState.RUNNING;
    }
    @Override protected void cleanup() {
        approach.close();
        TransportRuntime.cancel(this); super.cleanup();
    }
    @Override public void stop(LocalPlayer player,Task.StopReason why) {
        approach.stop(player,why);
        TransportRuntime.cancel(this); super.stop(player,why);
    }
    @Override protected Map<String,Object> resultData() {
        var result=new LinkedHashMap<String,Object>();
        if(observation!=null) result.put("control_analysis",observation.report());
        if(plan!=null) result.put("control_limitations",plan.limitations());
        if(session!=null) result.put("vehicle",session.diagnostics());
        result.put("driver_seat_approach",approach.evidence());
        if(outcome!=null) result.put("uncertain",outcome.uncertain());
        return result;
    }
    /** 面板行动行的一句话汇报；阶段来自内部检查/就位/驾驶状态，载具细节见 progress 的会话诊断。 */
    @Override public String describeCurrentAction() {
        if (observation == null) return "正在检查载具操控电路";
        if (session == null) return "正在走向驾驶座";
        return "正在驾驶载具行驶";
    }
    @Override protected String successMessage() { return "confirmed driver seating, native control and stopped arrival"; }
    @Override public Map<String,Object> progress() { return session==null ? super.progress():session.diagnostics(); }
}
