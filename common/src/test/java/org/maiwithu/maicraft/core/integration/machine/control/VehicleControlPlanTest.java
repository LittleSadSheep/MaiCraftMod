package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.List;
import java.util.Map;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;

public final class VehicleControlPlanTest {
    public static void main(String[] args) {
        var analog=circuit(THROTTLE,false);
        var plan=VehicleControlPlan.compile(analog);
        check(plan.usable(),"known analog disconnect circuit should compile");
        check(plan.inputs().getFirst().neutral()==15 && plan.inputs().getFirst().probes().equals(List.of(13.0)),
                "analog transmission neutral is high, with a low amplitude test toward drive");
        check(!VehicleControlPlan.compile(circuit(KEY,false)).usable(),"held-to-stop key cannot be safely released without an independent stop");
        var inverted=VehicleControlPlan.compile(circuit(KEY,true));
        check(inverted.usable() && inverted.inputs().getFirst().neutral()==0,"trace torch inversion to a safe release-to-stop key");
        var mixed=circuit(THROTTLE,false);
        mixed.add(new ControlCircuit.Node("steered",WHEEL,Map.of()));
        mixed.connect(new ControlCircuit.Edge("control","steered","redstone","direct/wheel_steer_positive",true));
        check(!VehicleControlPlan.compile(mixed).usable(),"conflicting neutral requirements must remain unresolved");
        var incomplete=circuit(THROTTLE,false); incomplete.unknown("remote receiver not observed");
        check(!VehicleControlPlan.compile(incomplete).usable(),"partial circuit does not authorize probes");
        System.out.println("VehicleControlPlanTest: passed");
    }
    private static ControlCircuit circuit(ControlCircuit.Kind kind,boolean inverter) {
        var c=new ControlCircuit();
        c.add(new ControlCircuit.Node("control",kind,Map.of("key",73)));
        c.add(new ControlCircuit.Node("transmission",TRANSMISSION,Map.of()));
        c.add(new ControlCircuit.Node("wheel",WHEEL,Map.of("wheel_item_present",true)));
        if(inverter) {
            c.add(new ControlCircuit.Node("torch",RELAY,Map.of()));
            c.connect(new ControlCircuit.Edge("control","torch","redstone","strong/invert_signal",true));
            c.connect(new ControlCircuit.Edge("torch","transmission","redstone","direct/analog_disconnect_at_15",true));
        } else c.connect(new ControlCircuit.Edge("control","transmission","redstone","direct/analog_disconnect_at_15",true));
        c.connect(new ControlCircuit.Edge("transmission","wheel","kinetic","native",true));
        return c;
    }
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
}
