package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;

/** Models Simulated's shaft and internal cog as different ports: signal 15 does not cut the through-shaft. */
final class ControlKineticPorts {
    private record Port(String id,Cell cell,Object entity,Object rotation) {}
    private ControlKineticPorts() {}
    static void connect(Level level,Map<BlockPos,Cell> cells,ControlCircuit circuit) {
        for(Cell cell:cells.values()) {
            if(!is(cell.entity(),CREATE+"kinetics.base.KineticBlockEntity")) continue;
            try {
                for(Port from:ports(cell)) {
                    var candidates=new ArrayList<BlockPos>();
                    for(Direction side:Direction.values()) candidates.add(cell.pos().relative(side));
                    @SuppressWarnings("unchecked") List<BlockPos> expanded=(List<BlockPos>)call(from.entity(),"addPropagationLocations",from.rotation(),cell.state(),candidates);
                    for(BlockPos pos:expanded) {
                        Cell target=cells.get(pos);
                        if(target==cell) continue;
                        if(target==null) {
                            if(!level.isLoaded(pos)) circuit.unknown("unloaded kinetic endpoint from "+from.id());
                            continue;
                        }
                        if(!is(target.entity(),CREATE+"kinetics.base.KineticBlockEntity")) continue;
                        for(Port to:ports(target)) if(!to.cell().pos().equals(field(from.entity(),"source")) && connected(level,from,to))
                            circuit.connect(new ControlCircuit.Edge(from.id(),to.id(),"kinetic","native_rotation_port_with_component_gating",true));
                    }
                }
                Object output=circuit.node(cell.id()).facts().get("controlled_output");
                if(output instanceof String id) {
                    String input=id.equals(cell.id()) ? cell.id()+"/cog":cell.id();
                    circuit.connect(new ControlCircuit.Edge(input,id,"kinetic","analog_transfer_disconnected_at_15",true));
                }
            } catch(RuntimeException | LinkageError failure) { circuit.unknown("kinetic ports unavailable: "+cell.id()); }
        }
    }
    private static List<Port> ports(Cell cell) {
        var shaft=new Port(cell.id(),cell,cell.entity(),cell.state().getBlock());
        if(!is(cell.entity(),SIM+"analog_transmission.AnalogTransmissionBlockEntity")) return List.of(shaft);
        return List.of(shaft,new Port(cell.id()+"/cog",cell,call(cell.entity(),"getExtraKinetics"),
                call(cell.state().getBlock(),"getExtraKineticsRotationConfiguration")));
    }
    private static boolean connected(Level level,Port from,Port to) {
        BlockPos delta=to.cell().pos().subtract(from.cell().pos());
        if(delta.distManhattan(BlockPos.ZERO)==1) {
            Direction side=Direction.fromDelta(delta.getX(),delta.getY(),delta.getZ());
            if(Boolean.TRUE.equals(call(from.rotation(),"hasShaftTowards",level,from.cell().pos(),from.cell().state(),side))
                    && Boolean.TRUE.equals(call(to.rotation(),"hasShaftTowards",level,to.cell().pos(),to.cell().state(),side.getOpposite()))) return true;
        }
        return Boolean.TRUE.equals(call(type(CREATE+"kinetics.RotationPropagator"),"isConnected",from.entity(),to.entity()));
    }
}
