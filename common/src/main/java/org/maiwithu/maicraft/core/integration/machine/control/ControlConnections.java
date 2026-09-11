package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;

/** Uses native frequency identity and kinetic ports; physical coordinates are projected before radio tests. */
public final class ControlConnections {
    private ControlConnections() {}
    public static void connect(Level level,Map<BlockPos,Cell> cells,List<Link> links,
                               Function<BlockPos,Vec3> world,ControlCircuit circuit) {
        ControlSignals.connect(level,cells,circuit);
        for(Link source:links) if(!source.receiver()) for(Link target:links) if(target.receiver()
                && source.frequency().equals(target.frequency())) {
            try {
                BlockPos from=(BlockPos)call(source.nativeLink(),"getLocation"), to=(BlockPos)call(target.nativeLink(),"getLocation");
                Cell receiver=cells.get(to);
                double range=((Number)call(field(field(call(type("com.simibubi.create.infrastructure.config.AllConfigs"),"server"),"logistics"),"linkRange"),"get")).doubleValue();
                Vec3 delta=world.apply(from).subtract(world.apply(to));
                String behavior="ordered_frequency_within_native_range";
                boolean matched=delta.length()<range;
                if(is(receiver.entity(),SIM+"redstone.AbstractLinkedReceiverBlockEntity")) {
                    // These receivers transform strength by distance/direction, not simply on/off.
                    circuit.connect(new ControlCircuit.Edge(source.node(),target.node(),"wireless","position_dependent_receiver",false));
                    continue;
                }
                if(matched) circuit.connect(new ControlCircuit.Edge(source.node(),target.node(),"wireless",behavior,true));
            } catch(RuntimeException | LinkageError failure) { circuit.unknown("radio evidence unavailable: "+source.node()+" -> "+target.node()); }
        }
        for(Cell source:cells.values()) {
            if(!is(source.entity(),CREATE+"kinetics.base.KineticBlockEntity")) continue;
            try {
                java.util.ArrayList<BlockPos> candidates=new java.util.ArrayList<>();
                for(Direction side:Direction.values()) candidates.add(source.pos().relative(side));
                @SuppressWarnings("unchecked") List<BlockPos> expanded=(List<BlockPos>)call(source.entity(),"addPropagationLocations",source.state().getBlock(),source.state(),candidates);
                for(BlockPos pos:expanded) {
                    Cell target=cells.get(pos);
                    if(target==null) {
                        if(!level.isLoaded(pos)) circuit.unknown("unloaded kinetic endpoint from "+source.id());
                        continue;
                    }
                    if(!is(target.entity(),CREATE+"kinetics.base.KineticBlockEntity")) continue;
                    boolean connected=kineticConnected(level,source,target);
                    if(connected) circuit.connect(new ControlCircuit.Edge(source.id(),target.id(),"kinetic","native_rotation_port_with_component_gating",true));
                }
            } catch(RuntimeException | LinkageError failure) { circuit.unknown("kinetic ports unavailable: "+source.id()); }
        }
    }
    private static boolean kineticConnected(Level level,Cell from,Cell to) {
        BlockPos delta=to.pos().subtract(from.pos());
        if(delta.distManhattan(BlockPos.ZERO)==1) {
            Direction side=Direction.fromDelta(delta.getX(),delta.getY(),delta.getZ());
            if(Boolean.TRUE.equals(call(from.state().getBlock(),"hasShaftTowards",level,from.pos(),from.state(),side))
                    && Boolean.TRUE.equals(call(to.state().getBlock(),"hasShaftTowards",level,to.pos(),to.state(),side.getOpposite()))) return true;
        }
        for(Object a:kinetics(from)) for(Object b:kinetics(to))
            if(Boolean.TRUE.equals(call(type(CREATE+"kinetics.RotationPropagator"),"isConnected",a,b))) return true;
        return false;
    }
    private static List<Object> kinetics(Cell cell) {
        return is(cell.entity(),SIM+"analog_transmission.AnalogTransmissionBlockEntity")
                ? List.of(cell.entity(),call(cell.entity(),"getExtraKinetics")) : List.of(cell.entity());
    }
}
