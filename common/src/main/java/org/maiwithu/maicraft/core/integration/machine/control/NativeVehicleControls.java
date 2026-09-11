package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import org.maiwithu.maicraft.client.actor.*;
import org.maiwithu.maicraft.core.integration.machine.assembly.ServerBlockEntityReceipts;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.entity.InputDriver;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;

/** Serialized native inputs. Analog changes await server block-entity updates; motion is a separate receipt. */
public final class NativeVehicleControls {
    private static final String PACKETS="dev.simulated_team.simulated.network.packets.";
    private final MachineControlInspection.Observation observation;
    private final VehicleControlPlan plan;
    private final Map<String,Double> applied=new LinkedHashMap<>();
    private final Map<BlockPos,Boolean> typewriters=new LinkedHashMap<>();
    private NativeActionReceipt receipt;
    private ServerBlockEntityReceipts.Watch watch;
    private LocalPlayerContext last;
    private String pendingId;
    private double pendingValue;
    private boolean changed,uncertain;
    public NativeVehicleControls(MachineControlInspection.Observation observation,VehicleControlPlan plan) {
        this.observation=observation; this.plan=plan;
    }
    public boolean apply(LocalPlayerContext ctx,SableStructureBridge.Structure structure,Map<String,Double> desired) {
        last=ctx;
        if(!poll(ctx)) return false;
        for(var input:plan.inputs()) {
            double value=input.clamp(desired.getOrDefault(input.id(),input.neutral()));
            var original=observation.cell(input.id()); BlockPos pos=original.pos();
            if(!structure.isLoaded(pos) || ctx.level().getBlockState(pos).getBlock()!=original.state().getBlock())
                throw new IllegalStateException("control block changed or unloaded");
            Object be=ctx.level().getBlockEntity(pos);
            if(be==null) throw new IllegalStateException("control state unavailable");
            if(applied.containsKey(input.id()) && Math.abs(applied.get(input.id())-value)<1e-5) continue;
            if(input.kind()==KEY && !Boolean.TRUE.equals(call(be,"checkUser",ctx.player().getUUID()))) {
                if(!ctx.player().getMainHandItem().isEmpty() || is(ctx.player().getOffhandItem().getItem(),
                        ControlComponents.CREATE+"redstone.link.controller.LinkedControllerItem"))
                    throw new IllegalStateException("typewriter requires an empty main hand and no offhand frequency-copy controller");
                if(Boolean.TRUE.equals(call(be,"isInUse"))) throw new IllegalStateException("typewriter is controlled by another player");
                if(!aim(ctx,structure,pos) || !ctx.mutationAvailable()) return false;
                var hit=DriverStation.hit(ctx.player(),structure,pos);
                receipt=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,new NativeConfirmation() {
                    public boolean requiresBlockAcknowledgement() { return true; }
                    public Verdict observe(LocalPlayerContext c) { return Boolean.TRUE.equals(call(be,"checkUser",c.player().getUUID())) ? Verdict.APPLIED:Verdict.PENDING; }
                },40);
                typewriters.put(pos,true); changed=true; return false;
            }
            if(!aim(ctx,structure,pos) || !ctx.mutationAvailable()) return false;
            if(input.kind()==STEERING_WHEEL && Boolean.TRUE.equals(field(be,"held")) && !applied.containsKey(input.id()))
                throw new IllegalStateException("steering wheel is already being held");
            final double target=value; final long tick=ctx.tickRevision();
            NativeConfirmation confirmation;
            Runnable submit;
            if(input.kind()==KEY) {
                int key=((Number)observation.circuit().node(input.id()).facts().get("key")).intValue();
                submit=()->send(ctx,construct(PACKETS+"linked_typewriter.TypewriterKeyInteractionPacket",pos,key,0,target>0?1:0));
                // This protocol has no per-key server acknowledgement. Only dispatch settles here.
                confirmation=c->c.tickRevision()>tick ? NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING;
            } else {
                if(input.kind()==STEERING_WHEEL && !Boolean.TRUE.equals(call(ctx.level().getBlockState(pos).getBlock(),
                        "lookingAtWheel",ctx.player(),pos,1F,ctx.level().getBlockState(pos)))) return false;
                watch=ServerBlockEntityReceipts.watch(ctx.level(),pos);
                var expectedWatch=watch;
                if(input.kind()==THROTTLE) {
                    boolean inverted=Boolean.parseBoolean(ControlComponents.property(ctx.level().getBlockState(pos),"inverted"));
                    int signal=(int)Math.round(target);
                    submit=()->send(ctx,construct(PACKETS+"ThrottleLeverSignalPacket",pos,inverted?15-signal:signal));
                    confirmation=c->expectedWatch.advanced() && ((Number)call(be,"getState")).intValue()==signal
                            ? NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING;
                } else {
                    submit=()->send(ctx,construct(PACKETS+"SteeringWheelPacket",false,(float)target,pos));
                    confirmation=c->expectedWatch.advanced() && Math.abs(((Number)field(be,"targetAngleToUpdate")).doubleValue()-target)<.01
                            ? NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING;
                }
            }
            pendingId=input.id(); pendingValue=value; changed=true;
            receipt=ctx.actions().submitControlProtocol(ctx,"vehicle native "+input.kind().name().toLowerCase(),submit,confirmation,40);
            return false;
        }
        return true;
    }
    private boolean poll(LocalPlayerContext ctx) {
        if(receipt==null) return true;
        receipt=ctx.actions().poll(ctx,receipt);
        if(!receipt.terminal()) return false;
        var completed=receipt; receipt=null;
        if(watch!=null) { watch.close(); watch=null; }
        if(completed.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            uncertain=true; pendingId=null; throw new IllegalStateException("vehicle input unconfirmed: "+completed.detail());
        }
        if(pendingId!=null) { applied.put(pendingId,pendingValue); pendingId=null; }
        return true;
    }
    private static boolean aim(LocalPlayerContext ctx,SableStructureBridge.Structure structure,BlockPos pos) {
        var point=DriverStation.aim(ctx.player(),structure,pos);
        if(point==null || point.distanceTo(ctx.player().getEyePosition())>ctx.player().blockInteractionRange())
            throw new IllegalStateException("seated control moved outside native reach");
        InputDriver.lookAt(ctx.player(),point);
        return DriverStation.hit(ctx.player(),structure,pos)!=null;
    }
    /** Release only this session's held gestures; persistent throttle position belongs to the real machine. */
    public void releaseGestures() {
        if(receipt!=null && last!=null) {
            org.maiwithu.maicraft.client.runtime.ClientRuntime.actor().activeContext()
                    .filter(c->c.player()==last.player()).ifPresent(c->c.actions().retireOneShotForTaskBoundary(c,receipt,"vehicle control ended"));
            receipt=null;
        }
        if(watch!=null) { watch.close(); watch=null; }
        if(last==null || last.minecraft().player!=last.player() || last.minecraft().level!=last.level()
                || last.player().connection==null) { applied.clear(); return; }
        for(var input:plan.inputs()) {
            var value=applied.get(input.id());
            if(value==null && !input.id().equals(pendingId)) continue;
            BlockPos pos=observation.cell(input.id()).pos();
            try {
                if(input.kind()==KEY) {
                    int key=((Number)observation.circuit().node(input.id()).facts().get("key")).intValue();
                    send(last,construct(PACKETS+"linked_typewriter.TypewriterKeyInteractionPacket",pos,key,0,0));
                } else if(input.kind()==STEERING_WHEEL) {
                    Object be=last.level().getBlockEntity(pos);
                    if(be!=null) send(last,construct(PACKETS+"SteeringWheelPacket",true,((Number)field(be,"targetAngleToUpdate")).floatValue(),pos));
                }
            } catch(RuntimeException failure) { uncertain=true; }
        }
        applied.clear();
    }
    private static void send(LocalPlayerContext ctx,Object packet) {
        ctx.player().connection.send(new ServerboundCustomPayloadPacket((CustomPacketPayload)packet));
    }
    public boolean changed() { return changed; }
    public boolean uncertain() { return uncertain; }
    public Map<String,Double> applied() { return Map.copyOf(applied); }
}
