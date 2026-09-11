package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;

/** Directed ports, including strong power through one conductor; never floods through solid blocks. */
public final class ControlSignals {
    private ControlSignals() {}
    public static void connect(Level level, Map<BlockPos,Cell> cells, ControlCircuit circuit) {
        for(Cell source:cells.values()) for(Direction direction:Direction.values()) {
            Cell target=cells.get(source.pos().relative(direction));
            if(target==null) continue;
            if(outputs(source,direction,false)) edge(circuit,source,target,direction,"direct");
            if(outputs(source,direction,true) && target.state().isRedstoneConductor(level,target.pos())) {
                for(Direction side:Direction.values()) {
                    Cell recipient=cells.get(target.pos().relative(side));
                    if(recipient!=null && recipient!=source) edge(circuit,source,recipient,side,"strong_via:"+target.id());
                }
            }
            if(kind(source)==STEERING_WHEEL && target.state().getBlock() instanceof ComparatorBlock
                    && facing(target.state())==direction.getOpposite()) {
                circuit.connect(new ControlCircuit.Edge(source.id(),target.id(),"comparator",
                        "directional_angle:"+direction.getOpposite().getSerializedName(),true));
            }
        }
    }
    static boolean outputs(Cell source,Direction toward,boolean strong) {
        var state=source.state(); var block=state.getBlock(); var kind=kind(source);
        if(kind==RECEIVER) return !strong || toward==facing(state).getOpposite();
        if(kind==THROTTLE || block instanceof ButtonBlock || block instanceof LeverBlock)
            return !strong || toward==attachment(state);
        if(block instanceof DiodeBlock) return toward==facing(state).getOpposite();
        if(block instanceof RedstoneTorchBlock) return strong ? toward==Direction.UP
                : toward!=(block instanceof RedstoneWallTorchBlock ? facing(state).getOpposite() : Direction.DOWN);
        if(block instanceof RedStoneWireBlock) {
            if(toward==Direction.DOWN) return true;
            if(toward==Direction.UP) return false;
            return !"none".equals(property(state,toward.getSerializedName())) && !property(state,toward.getSerializedName()).isEmpty();
        }
        return false;
    }
    private static void edge(ControlCircuit graph,Cell source,Cell target,Direction toward,String path) {
        String behavior=input(target,toward.getOpposite());
        if(behavior==null) return;
        String targetId=target.id();
        if(behavior.equals("analog_disconnect_at_15")) {
            Object output=graph.node(target.id()).facts().get("controlled_output");
            if(!(output instanceof String id)) { graph.connect(new ControlCircuit.Edge(source.id(),targetId,"redstone","unknown_analog_power_direction",false)); return; }
            targetId=id;
        }
        graph.connect(new ControlCircuit.Edge(source.id(),targetId,"redstone",path+"/"+behavior,true));
    }
    /** Side is the face of the consumer towards the signal source. */
    static String input(Cell target,Direction side) {
        var kind=kind(target); var block=target.state().getBlock();
        if(kind==TRANSMITTER || kind==WIRE) return "signal";
        if(block instanceof RedstoneTorchBlock) return side==(block instanceof RedstoneWallTorchBlock
                ? facing(target.state()).getOpposite():Direction.DOWN) ? "invert_signal":null;
        if(kind==RELAY) return side==facing(target.state()) ? "diode_input" : null;
        if(kind==WHEEL) {
            Direction facing=facing(target.state());
            if(side==Direction.UP) return "wheel_brake";
            if(side==facing.getClockWise()) return "wheel_steer_positive";
            if(side==facing.getCounterClockWise()) return "wheel_steer_negative";
            return null;
        }
        if(ControlReflection.is(target.entity(),SIM+"analog_transmission.AnalogTransmissionBlockEntity")) return "analog_disconnect_at_15";
        if(ControlReflection.is(target.entity(),CREATE+"kinetics.transmission.ClutchBlockEntity")) return "clutch_disconnect_when_powered";
        if(ControlReflection.is(target.entity(),CREATE+"kinetics.transmission.GearshiftBlockEntity")) return "reverse_when_powered";
        // Unknown consumers are evidence, but cannot be used to prove an actuator connection.
        return null;
    }
    static Direction facing(BlockState state) {
        Direction direction=Direction.byName(property(state,"facing"));
        return direction==null ? Direction.NORTH : direction;
    }
    static Direction attachment(BlockState state) {
        return switch(property(state,"face")) {
            case "floor" -> Direction.DOWN;
            case "ceiling" -> Direction.UP;
            default -> facing(state).getOpposite();
        };
    }
}
