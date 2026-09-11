package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.*;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.entity.InputDriver;

/** Seat confirmation and native control share one cancellable transport lease. */
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
            var aim=DriverStation.aim(ctx.player(),structure,station.seat());
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
        if(delta.lengthSqr()>.00001 && !clearAhead(ctx,structure,delta)) pilot.stop("vehicle path is obstructed or unobserved");
        Vec3 forward=structure.pose().normalToWorld(new Vec3(0,0,1));
        boolean applied=controls.apply(ctx,structure,pilot.command());
        pilot.observe(ctx.tickRevision(),new VehicleFeedbackPilot.Sample(position,Math.atan2(forward.x,forward.z)),applied);
        if(pilot.terminal()) return finish(pilot.succeeded(),pilot.succeeded()?"vehicle_arrived":"vehicle_control_stopped",pilot.failure(),!pilot.succeeded());
        return Result.running(phase());
    }
    private boolean clearAhead(LocalPlayerContext ctx,SableStructureBridge.Structure structure,Vec3 delta) {
        if(structure.worldBounds()==null) return false;
        var next=structure.worldBounds().expandTowards(delta.scale(12)).deflate(.08);
        return ctx.level().hasChunksAt(net.minecraft.core.BlockPos.containing(next.minX,next.minY,next.minZ),
                net.minecraft.core.BlockPos.containing(next.maxX,next.maxY,next.maxZ)) && ctx.level().noCollision(ctx.player(),next);
    }
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
        return hand.started() && ctx.minecraft().screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || TransportSession.super.allowsCurrentScreen(ctx);
    }
    @Override public String phase() { return !seated ? "boarding_driver_seat":pilot.phase().name().toLowerCase(); }
    @Override public Map<String,Object> diagnostics() {
        return Map.of("structure_id",observation.structureId().toString(),"driver_seat",station.seat().toShortString(),
                "seat_confirmed",seated,"phase",phase(),"control_inputs",plan.inputs(),"applied",controls.applied(),"motion",pilot.diagnostics());
    }
}
