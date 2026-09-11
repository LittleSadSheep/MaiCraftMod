package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlComponents.*;

/** Detects rewiring, frequency changes and altered configuration before continued control. */
public final class VehicleCircuitGuard {
    private final MachineControlInspection.Observation original;
    private final Map<BlockPos,String> states=new LinkedHashMap<>();
    public VehicleCircuitGuard(Level level,MachineControlInspection.Observation observation) {
        original=observation;
        for(Cell cell:observation.cells().values()) {
            states.put(cell.pos(),signature(cell.state()));
            if(kind(cell)==ControlCircuit.Kind.OTHER) continue;
            for(Direction side:Direction.values()) {
                BlockPos pos=cell.pos().relative(side);
                if(observation.structure().isLoaded(pos)) states.putIfAbsent(pos,signature(level.getBlockState(pos)));
            }
        }
    }
    public String changed(Level level,SableStructureBridge.Structure live) {
        if(!original.structure().storageBounds().equals(live.storageBounds())) return "structure bounds changed";
        for(var entry:states.entrySet()) {
            if(!live.isLoaded(entry.getKey())) return "circuit cell unloaded";
            if(!signature(level.getBlockState(entry.getKey())).equals(entry.getValue())) return "circuit topology or block configuration changed";
        }
        for(Cell cell:original.cells().values()) {
            var kind=kind(cell);
            if(kind!=ControlCircuit.Kind.KEY && kind!=ControlCircuit.Kind.RECEIVER && kind!=ControlCircuit.Kind.TRANSMITTER
                    && kind!=ControlCircuit.Kind.STEERING_WHEEL && !ControlReflection.is(cell.entity(),SIM+"analog_transmission.AnalogTransmissionBlockEntity")) continue;
            var circuit=new ControlCircuit();
            read(level,new Cell(cell.pos(),level.getBlockState(cell.pos()),level.getBlockEntity(cell.pos())),circuit);
            if(!circuit.analyze().complete()) return "control configuration is no longer readable";
            var previous=original.circuit().nodes().stream().filter(n->n.id().equals(cell.id())||n.id().startsWith(cell.id()+"/")).toList();
            if(previous.size()!=circuit.nodes().size()) return "typewriter bindings changed";
            for(var node:circuit.nodes()) {
                var old=original.circuit().node(node.id());
                if(old==null || !configuration(old.facts()).equals(configuration(node.facts()))) return "control frequency or limits changed";
            }
        }
        return null;
    }
    static Map<String,Object> configuration(Map<String,Object> facts) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:Set.of("frequency","key","angle_limit","min_range","max_range","signal_rule","controlled_output"))
            if(facts.containsKey(key)) result.put(key,facts.get(key));
        return result;
    }
    static String signature(BlockState state) {
        var properties=new java.util.TreeMap<String,String>();
        state.getValues().forEach((property,value)-> {
            if(!Set.of("powered","power","lit").contains(property.getName())) properties.put(property.getName(),value.toString());
        });
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())+properties.toString();
    }
}
