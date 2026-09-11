package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class VehicleCircuitGuardTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var wire=Blocks.REDSTONE_WIRE.defaultBlockState();
        check(VehicleCircuitGuard.signature(wire).equals(VehicleCircuitGuard.signature(wire.setValue(BlockStateProperties.POWER,12))),
                "ordinary observed signal changes are not rewiring");
        check(!VehicleCircuitGuard.signature(Blocks.LEVER.defaultBlockState()).equals(VehicleCircuitGuard.signature(Blocks.STONE.defaultBlockState())),
                "replacement invalidates the circuit");
        var original=Map.<String,Object>of("frequency",List.of("iron#-1","gold#-1"),"key",73,"rpm",16);
        var telemetry=Map.<String,Object>of("frequency",List.of("iron#-1","gold#-1"),"key",73,"rpm",32);
        var rebound=Map.<String,Object>of("frequency",List.of("gold#-1","iron#-1"),"key",73,"rpm",16);
        check(VehicleCircuitGuard.configuration(original).equals(VehicleCircuitGuard.configuration(telemetry)),"rpm changes do not destroy control identity");
        check(!VehicleCircuitGuard.configuration(original).equals(VehicleCircuitGuard.configuration(rebound)),"ordered frequency changes invalidate the request");
        var circuit=new ControlCircuit();
        circuit.add(new ControlCircuit.Node("throttle",ControlCircuit.Kind.THROTTLE,Map.of()));
        for(String id:List.of("shaft","cog")) circuit.add(new ControlCircuit.Node(id,ControlCircuit.Kind.TRANSMISSION,Map.of()));
        for(String id:List.of("upstream_wheel","controlled_wheel")) circuit.add(new ControlCircuit.Node(id,ControlCircuit.Kind.WHEEL,Map.of()));
        circuit.connect(new ControlCircuit.Edge("throttle","cog","redstone","direct/analog_disconnect_at_15",true));
        circuit.connect(new ControlCircuit.Edge("shaft","cog","kinetic","conditional_transfer",true));
        circuit.connect(new ControlCircuit.Edge("shaft","upstream_wheel","kinetic","through_shaft",true));
        circuit.connect(new ControlCircuit.Edge("cog","controlled_wheel","kinetic","native",true));
        check(circuit.analyze().routes().size()==1 && circuit.analyze().routes().getFirst().actuator().equals("controlled_wheel"),
                "internal cog control must not claim to cut a through-shaft or its upstream wheels");
        System.out.println("VehicleCircuitGuardTest: passed");
    }
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
}
