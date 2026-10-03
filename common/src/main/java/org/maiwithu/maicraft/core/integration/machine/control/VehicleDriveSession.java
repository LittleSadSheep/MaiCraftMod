package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.*;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.maiwithu.maicraft.core.task.physics.PhysicalStructureApproach;

/** 座位确认和原生控制共用一个可取消的交通工具租约。 */
public final class VehicleDriveSession implements TransportSession {
    private final MachineControlInspection.Observation observation;
    private final VehicleControlPlan plan;
    private final DriverStation station;
    private final NativeVehicleControls controls;
    private final VehicleFeedbackPilot pilot;
    private final FirstPersonActionGate hand=new FirstPersonActionGate();
    private NativeActionReceipt mount;
    private Result terminal;
    private boolean seated,stopping;
    private int emptySlot=-1;
    private long started=-1;
    private Vec3 previous;
    private VehicleCircuitGuard circuitGuard;
    private Map<String,Object> obstruction=Map.of();
    private final VehicleMotionEvidence motionEvidence=new VehicleMotionEvidence();
    public VehicleDriveSession(MachineControlInspection.Observation observation,VehicleControlPlan plan,DriverStation station,Vec3 target) {
        this.observation=observation; this.plan=plan; this.station=station;
        controls=new NativeVehicleControls(observation,plan); pilot=new VehicleFeedbackPilot(plan,target);
    }
    @Override public Result tick(LocalPlayerContext ctx) {
        if(terminal!=null) return terminal;
        if(started<0) started=ctx.tickRevision();
        var structure=SableStructureBridge.find(ctx.level(),observation.structureId());
        if(structure==null || structure.pose()==null || !structure.isLoaded(station.seat()))
            return finish(false,"vehicle_structure_lost","selected structure or driver seat is no longer observable",true);
        if(circuitGuard==null) circuitGuard=new VehicleCircuitGuard(ctx.level(),observation);
        if((ctx.tickRevision()-started)%10==0) {
            String changed=circuitGuard.changed(ctx.level(),structure);
            if(changed!=null) return finish(false,"vehicle_circuit_changed",changed,true);
        }
        InputDriver.halt(ctx.player());
        if(!seated) {
            // 施工和换位可能留下潜行输入；上车前释放并等待真实姿态恢复，避免原生交互成为下车或拆卸。
            InputDriver.sneak(ctx.player(),false);
            if(stopping) return finish(false,"vehicle_cancelled","cancelled before driving",mount!=null);
            if(ctx.tickRevision()-started>240) return finish(false,"driver_seat_unconfirmed","native seating did not confirm",mount!=null);
            if(mount!=null) {
                mount=ctx.actions().poll(ctx,mount);
                if(!mount.terminal()) return Result.running("confirming_driver_seat");
                if(mount.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED)
                    return finish(false,"driver_seat_unconfirmed",mount.detail(),true);
                mount=null;
            }
            if(!DriverStation.unoccupied(ctx.player(),station.seat())) return finish(false,"driver_seat_occupied","seat became occupied",false);
            if(hand.pending() || !ctx.player().getMainHandItem().isEmpty()) {
                if(emptySlot<0) for(int i=0;i<36;i++) if(ctx.player().getInventory().getItem(i).isEmpty()) { emptySlot=i; break; }
                if(emptySlot<0) return finish(false,"empty_hand_unavailable","an empty hand is required to use these controls",false);
                var selection=hand.select(ctx.player(),emptySlot);
                if(selection==FirstPersonActionGate.Status.FAILED) return finish(false,"empty_hand_unavailable",hand.failure(),false);
                return Result.running("preparing_empty_hand");
            }
            if(station.seated(ctx.player())) { seated=true; return Result.running("driver_seat_confirmed"); }
            if(ctx.player().isShiftKeyDown()) return Result.running("releasing_sneak_before_seating");
            var aim=PhysicalStructureApproach.visibleAim(ctx.player(),structure,station.seat(),ctx.player().getEyePosition());
            if(aim==null) return finish(false,"driver_seat_lost","seat geometry unavailable",false);
            InputDriver.lookAt(ctx.player(),aim);
            var hit=DriverStation.hit(ctx.player(),structure,station.seat());
            if(hit==null || !ctx.mutationAvailable()) return Result.running("aiming_at_driver_seat");
            Object leashed=ControlReflection.call(ControlReflection.type(ControlComponents.CREATE+"contraptions.actors.seat.SeatBlock"),
                    "getLeashed",ctx.level(),ctx.player());
            if(Boolean.TRUE.equals(ControlReflection.call(leashed,"isPresent")))
                return finish(false,"seat_would_capture_leashed_entity","native seat use would seat a leashed entity instead of the driver",false);
            mount=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,
                    NativeConfirmation.serverObservedEntity(c->station.seated(c.player())
                            ? NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING),40);
            return Result.running("confirming_driver_seat");
        }
        if(!station.seated(ctx.player())) return finish(false,"driver_seat_lost","confirmed riding relationship ended",true);
        if(ctx.tickRevision()-started>12000) { stopping=true; pilot.stop("driving time budget exhausted"); }
        Vec3 position=structure.pose().toWorld(Vec3.atBottomCenterOf(station.seat()));
        Vec3 delta=previous==null ? Vec3.ZERO:position.subtract(previous); previous=position;
        Vec3 up=structure.pose().normalToWorld(new Vec3(0,1,0));
        if(up.y<.7) pilot.stop("structure tilt exceeds the current controller's observed stability range");
        // 上车引起的悬挂沉降先由中性工况消散；推进校准开始后再外推行驶碰撞，不能把沉降误作驶向地面的路线。
        if(pilot.phase()!=VehicleFeedbackPilot.Phase.BASELINE&&delta.lengthSqr()>.00001 && !clearAhead(ctx,structure,delta))
            pilot.stop("vehicle path is obstructed or unobserved");
        Vec3 forward=structure.pose().normalToWorld(new Vec3(0,0,1));
        // 回执保留实测行驶转角、路程和最大倾斜，便于模型区分部件校准成功与车体真正完成路线。
        motionEvidence.observe(position,Math.atan2(forward.x,forward.z),up,pilot.phase()==VehicleFeedbackPilot.Phase.DRIVE);
        boolean applied=controls.apply(ctx,structure,pilot.command());
        pilot.observe(ctx.tickRevision(),new VehicleFeedbackPilot.Sample(position,Math.atan2(forward.x,forward.z)),applied);
        if(pilot.terminal()) return finish(pilot.succeeded(),pilot.succeeded()?"vehicle_arrived":"vehicle_control_stopped",pilot.failure(),!pilot.succeeded());
        return Result.running(phase());
    }
    private boolean clearAhead(LocalPlayerContext ctx,SableStructureBridge.Structure structure,Vec3 delta) {
        if(structure.worldBounds()==null){obstruction=Map.of("reason","structure_bounds_unavailable");return false;}
        if(!Double.isFinite(delta.lengthSqr())||delta.lengthSqr()>1) {
            obstruction=Map.of("reason","motion_exceeds_local_corridor","motion_delta",List.of(delta.x,delta.y,delta.z));return false;
        }
        var next=structure.worldBounds().expandTowards(delta.scale(12)).deflate(.08);
        // 按实际客户端区块缓存检查整段路线，避免 ClientLevel 的宽松区块接口把未知地形当成空气。
        for(int x=(int)Math.floor(next.minX)>>4;x<=((int)Math.floor(next.maxX)>>4);x++)
            for(int z=(int)Math.floor(next.minZ)>>4;z<=((int)Math.floor(next.maxZ)>>4);z++)
                if(!ctx.level().getChunkSource().hasChunk(x,z)){obstruction=Map.of("reason","unloaded","chunk",List.of(x,z));return false;}
        if(ctx.level().noCollision(ctx.player(),next))return true;
        // 记录触发停车的真实查询范围及第一个原生阻挡形状，模型才能区分施工脚手架、地面与未知实体阻挡。
        var facts=new LinkedHashMap<String,Object>();
        facts.put("reason","native_collision");facts.put("swept_bounds",bounds(next));facts.put("motion_delta",List.of(delta.x,delta.y,delta.z));
        for(var shape:ctx.level().getBlockCollisions(ctx.player(),next))if(!shape.isEmpty()) {
            facts.put("first_block_collision_bounds",bounds(shape.bounds()));break;
        }
        obstruction=Map.copyOf(facts);return false;
    }
    private static List<Double> bounds(AABB box){return List.of(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ);}
    private Result finish(boolean success,String code,String detail,boolean uncertain) {
        hand.reset();
        controls.releaseGestures();
        if(success && controls.uncertain()) return terminal=Result.failed("vehicle_release_unconfirmed","arrival observed but held-input cleanup is uncertain",true,true);
        return terminal=success ? Result.success("driver seat remained confirmed; destination and stopped motion observed")
                : Result.failed(code,detail,controls.changed()||seated,uncertain||controls.uncertain());
    }
    @Override public void requestStop() { stopping=true; pilot.cancel(); }
    @Override public void abandon() { stopping=true; finish(false,"vehicle_control_transferred","held inputs released; persistent machine settings require observation",controls.changed()); }
    @Override public boolean safeToInterrupt() { return terminal!=null || !seated && mount==null; }
    @Override public boolean livenessActive() { return terminal==null; }
    @Override public boolean allowsCurrentScreen(LocalPlayerContext ctx) {
        return hand.started() && ctx.minecraft().screen instanceof InventoryScreen
                || TransportSession.super.allowsCurrentScreen(ctx);
    }
    @Override public String phase() { return !seated ? "boarding_driver_seat":pilot.phase().name().toLowerCase(); }
    @Override public Map<String,Object> diagnostics() {
        return Map.of("structure_id",observation.structureId().toString(),"driver_seat",station.seat().toShortString(),
                "seat_confirmed",seated,"phase",phase(),"control_inputs",plan.inputs(),"applied",controls.applied(),"motion",pilot.diagnostics(),
                "native_control_effects",controls.effects(),"obstruction",obstruction,"motion_evidence",motionEvidence.facts());
    }
}
