package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;

public final class ControlCircuitTest {
    public static void main(String[] args) {
        var circuit = new ControlCircuit();
        add(circuit,"seat",SEAT); add(circuit,"wheel",WHEEL);
        check(!circuit.analyze().controllableCandidate(),"a seat and wheels do not establish controls");
        add(circuit,"custom_key",KEY); add(circuit,"receiver",RECEIVER); add(circuit,"gear",TRANSMISSION);
        edge(circuit,"custom_key","receiver","wireless","ordered native frequency",true);
        edge(circuit,"receiver","gear","redstone","attached powered face",true);
        edge(circuit,"gear","wheel","kinetic","native shaft connection",false);
        check(circuit.analyze().routes().isEmpty(),"geometric proximity is not a drive connection");
        edge(circuit,"gear","wheel","kinetic","native shaft connection",true);
        edge(circuit,"wheel","gear","kinetic","native shaft connection",true);
        var report = circuit.analyze();
        check(report.routes().size()==1 && report.routes().getFirst().path().size()==3,"trace the full chain through cycles");
        check(report.controllableCandidate() && !report.complete(),"retain unresolved evidence alongside proven routes");
        add(circuit,"other_key",KEY);
        check(circuit.analyze().unconnectedControls().contains("other_key"),"unused keys stay distinguishable");
        var workshop = new ControlCircuit();
        add(workshop,"wheel_control",STEERING_WHEEL); add(workshop,"bearing",JOINT);
        edge(workshop,"wheel_control","bearing","kinetic","native",true);
        check(!workshop.analyze().controllableCandidate(),"a workshop bearing is not a vehicle");
        add(workshop,"unconnected_wheel",WHEEL);
        check(!workshop.analyze().controllableCandidate(),"an unrelated wheel cannot turn a controlled workshop bearing into a vehicle");
        try { edge(workshop,"missing","bearing","kinetic","native",true); throw new AssertionError("dangling reference"); }
        catch(IllegalArgumentException expected) { }
        System.out.println("ControlCircuitTest: passed");
    }
    private static void add(ControlCircuit c,String id,ControlCircuit.Kind kind) { c.add(new ControlCircuit.Node(id,kind,Map.of())); }
    private static void edge(ControlCircuit c,String a,String b,String medium,String behavior,boolean verified) {
        c.connect(new ControlCircuit.Edge(a,b,medium,behavior,verified));
    }
    private static void check(boolean value,String why) { if(!value) throw new AssertionError(why); }
}
