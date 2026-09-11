package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;

/** Reads synchronized controls and their native frequency objects without changing them. */
public final class ControlComponents {
    public static final String CREATE = "com.simibubi.create.content.";
    public static final String SIM = "dev.simulated_team.simulated.content.blocks.";
    public record Cell(BlockPos pos, BlockState state, BlockEntity entity) {
        public Cell { pos = pos.immutable(); }
        public String id() { return pos.getX()+","+pos.getY()+","+pos.getZ(); }
        public String blockId() { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    }
    public record Link(String node, Object nativeLink, Object frequency, boolean receiver) {}
    private ControlComponents() {}
    public static ControlCircuit.Kind kind(Cell cell) {
        Object be=cell.entity(), block=cell.state().getBlock();
        if (is(block,CREATE+"contraptions.actors.seat.SeatBlock")) return SEAT;
        if (is(be,SIM+"redstone.linked_typewriter.LinkedTypewriterBlockEntity")) return KEY;
        if (is(be,SIM+"throttle_lever.ThrottleLeverBlockEntity")) return THROTTLE;
        if (is(be,SIM+"steering_wheel.SteeringWheelBlockEntity")) return STEERING_WHEEL;
        if (is(be,CREATE+"redstone.link.RedstoneLinkBlockEntity"))
            return Boolean.parseBoolean(property(cell.state(),"receiver")) ? RECEIVER : TRANSMITTER;
        if (is(be,SIM+"redstone.AbstractLinkedReceiverBlockEntity")) return RECEIVER;
        if (is(be,"dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity")) return WHEEL;
        if (is(be,"dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity")
                || is(be,"dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity")) return PROPELLER;
        if (is(be,CREATE+"contraptions.bearing.MechanicalBearingBlockEntity")) return JOINT;
        if (is(be,CREATE+"kinetics.base.KineticBlockEntity")) return TRANSMISSION;
        if (block instanceof net.minecraft.world.level.block.RedStoneWireBlock) return WIRE;
        if (block instanceof net.minecraft.world.level.block.DiodeBlock || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock) return RELAY;
        return OTHER;
    }
    public static List<Link> read(Level level, Cell cell, ControlCircuit circuit) {
        var kind=kind(cell); var facts=new LinkedHashMap<String,Object>();
        facts.put("block_id",cell.blockId()); facts.put("storage_position",List.of(cell.pos().getX(),cell.pos().getY(),cell.pos().getZ()));
        var properties=new LinkedHashMap<String,String>();
        cell.state().getValues().forEach((p,v)->properties.put(p.getName(),serialized(p,v))); facts.put("properties",properties);
        List<Link> links=new ArrayList<>();
        try {
            Object be=cell.entity();
            if(kind==SEAT) {
                facts.put("occupied",level.getEntities((net.minecraft.world.entity.Entity)null,new net.minecraft.world.phys.AABB(cell.pos()),
                        e->is(e,CREATE+"contraptions.actors.seat.SeatEntity")&&!e.getPassengers().isEmpty()).size()>0);
            } else if (kind==KEY) {
                facts.put("in_use",call(be,"isInUse"));
                Object entries=call(be,"getTypewriterEntries");
                List<?> keys=(List<?>)call(entries,"getEntries");
                if(keys.size()>256) circuit.unknown(cell.id()+": typewriter binding observation truncated");
                for (Object key : keys.subList(0,Math.min(256,keys.size()))) {
                    int code=((Number)call(key,"getGLFWKeyCode")).intValue();
                    Object frequency=call(key,"getNetworkKey");
                    var keyFacts=new LinkedHashMap<>(facts); keyFacts.put("key",code); keyFacts.put("frequency",frequency(frequency));
                    String id=cell.id()+"/key/"+code;
                    circuit.add(new ControlCircuit.Node(id,KEY,keyFacts)); links.add(new Link(id,key,frequency,false));
                }
                // The housing is separate from its independently bound controls.
                kind=OTHER;
            } else if (kind==RECEIVER || kind==TRANSMITTER) {
                Object behavior=call(type("com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour"),"get",
                        level,cell.pos(),field(type(CREATE+"redstone.link.LinkBehaviour"),"TYPE"));
                if (behavior==null) throw new IllegalStateException("link behavior not synchronized");
                Object frequency=call(behavior,"getNetworkKey");
                facts.put("frequency",frequency(frequency)); facts.put("listening",call(behavior,"isListening"));
                if(Boolean.TRUE.equals(facts.get("listening"))!=(kind==RECEIVER)) circuit.unknown(cell.id()+": receiver mode and native link behavior disagree");
                links.add(new Link(cell.id(),behavior,frequency,kind==RECEIVER));
                if(kind==RECEIVER) facts.put("received_signal",call(be,"getReceivedSignal"));
                if(is(be,SIM+"redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity")) {
                    facts.put("min_range",field(be,"minRange")); facts.put("max_range",field(be,"maxRange"));
                    facts.put("signal_rule","distance_attenuated");
                } else if(is(be,SIM+"redstone.directional_receiver.DirectionalLinkedReceiverBlockEntity")) facts.put("signal_rule","direction_attenuated");
            } else if(kind==THROTTLE) {
                facts.put("signal",call(be,"getState")); facts.put("input_range",List.of(0,15));
                facts.put("inverted",Boolean.parseBoolean(property(cell.state(),"inverted")));
            } else if(kind==STEERING_WHEEL) {
                facts.put("angle",call(be,"getInteractionAngle",1F)); facts.put("target_angle",field(be,"targetAngleToUpdate"));
                facts.put("held",field(be,"held")); facts.put("angle_limit",call(field(be,"angleInput"),"getValue"));
                facts.put("signal_rule","kinetic_angle_and_directional_comparator_output");
            }
            if(is(be,CREATE+"kinetics.base.KineticBlockEntity")) {
                facts.put("rpm",call(be,"getSpeed")); facts.put("overstressed",call(be,"isOverStressed"));
                Object source=field(be,"source"); if(source instanceof BlockPos pos) facts.put("kinetic_source",List.of(pos.getX(),pos.getY(),pos.getZ()));
            }
            if(is(be,SIM+"analog_transmission.AnalogTransmissionBlockEntity")) {
                facts.put("signal_rule","0=direct; 1..14=variable_ratio; 15=disconnected");
                facts.put("neutral_signal",15);
                Object extra=call(be,"getExtraKinetics");
                Object shaftSource=field(be,"source"), cogSource=field(extra,"source");
                boolean shaftInput=shaftSource instanceof BlockPos pos && !pos.equals(cell.pos());
                boolean cogInput=cogSource instanceof BlockPos pos && !pos.equals(cell.pos());
                if(shaftInput!=cogInput) facts.put("controlled_output",cell.id()+(shaftInput?"/cog":""));
                else circuit.unknown(cell.id()+": analog transmission input/output power direction unavailable or multiply powered");
                var cogFacts=new LinkedHashMap<>(facts); cogFacts.put("port","internal_cogwheel");
                cogFacts.put("rpm",call(extra,"getSpeed"));
                circuit.add(new ControlCircuit.Node(cell.id()+"/cog",TRANSMISSION,cogFacts));
            }
            if(kind==WHEEL) {
                facts.put("wheel_item_present",!((ItemStack)call(be,"getHeldItem")).isEmpty());
                facts.put("signal_rule","above=brake; clockwise_side-minus-counterclockwise_side=steering; shaft=rpm");
            }
        } catch (RuntimeException | LinkageError unknown) {
            facts.put("read_error",unknown.getMessage()==null ? unknown.getClass().getSimpleName() : unknown.getMessage());
            circuit.unknown(cell.id()+": native component state unavailable");
        }
        circuit.add(new ControlCircuit.Node(cell.id(),kind,facts));
        return links;
    }
    public static String property(BlockState state,String name) {
        return state.getValues().entrySet().stream().filter(e->e.getKey().getName().equals(name))
                .map(e->serialized(e.getKey(),e.getValue())).findFirst().orElse("");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String serialized(net.minecraft.world.level.block.state.properties.Property property,Comparable value) {
        return property.getName(value);
    }
    private static List<Map<String,Object>> frequency(Object pair) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(String half:List.of("getFirst","getSecond")) {
            ItemStack stack=(ItemStack)call(call(pair,half),"getStack");
            var color=stack.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
            result.add(Map.of("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"dyed_color",color==null?-1:color.rgb()));
        }
        return result;
    }
}
