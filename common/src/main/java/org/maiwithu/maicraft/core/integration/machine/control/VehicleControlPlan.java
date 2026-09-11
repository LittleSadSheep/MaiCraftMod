package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;

/** Derives control ranges and neutral signals from the connected mechanism, never from key names. */
public record VehicleControlPlan(List<Input> inputs, List<String> limitations) {
    public record Input(String id,ControlCircuit.Kind kind,double neutral,double minimum,double maximum,
                        List<String> actuators,boolean propulsion) {
        public Input { actuators=List.copyOf(actuators); }
        public double clamp(double value) { return Math.clamp(value,minimum,maximum); }
        public List<Double> probes() {
            if(kind==KEY) return List.of(1-neutral);
            double step=kind==THROTTLE ? 2 : Math.min(15,maximum);
            var values=new ArrayList<Double>();
            if(neutral-step>=minimum) values.add(neutral-step);
            if(neutral+step<=maximum) values.add(neutral+step);
            return values;
        }
    }
    public VehicleControlPlan { inputs=List.copyOf(inputs); limitations=List.copyOf(limitations); }
    public Map<String,Double> neutral() {
        var result=new LinkedHashMap<String,Double>(); inputs.forEach(i->result.put(i.id(),i.neutral())); return result;
    }
    public static VehicleControlPlan compile(ControlCircuit circuit) {
        var analysis=circuit.analyze(); List<Input> inputs=new ArrayList<>(); List<String> issues=new ArrayList<>();
        if(!analysis.complete()) issues.addAll(analysis.unknowns());
        if(!analysis.controllableCandidate()) issues.add("no control path to a locomotion-equipped structure");
        if(analysis.routes().stream().map(r->circuit.node(r.actuator())).filter(n->n.kind()==WHEEL)
                .anyMatch(n->Boolean.FALSE.equals(n.facts().get("wheel_item_present"))))
            issues.add("a wheel mount has no installed wheel item");
        for(var node:circuit.nodes()) {
            if(!node.control()) continue;
            var routes=analysis.routes().stream().filter(r->r.control().equals(node.id())).toList();
            if(routes.isEmpty()) continue;
            boolean disconnected=false,ordinary=false,propulsion=false;
            for(var route:routes) {
                if(route.path().stream().anyMatch(e->e.medium().equals("wireless") && e.behavior().contains("attenuated")))
                    issues.add(node.id()+": position-dependent wireless gain requires a dynamic control model");
                boolean brake=route.path().stream().anyMatch(e->e.behavior().endsWith("/wheel_brake"));
                boolean neutralHigh=brake || route.path().stream().anyMatch(e->e.behavior().endsWith("/analog_disconnect_at_15")
                        || e.behavior().endsWith("/clutch_disconnect_when_powered"));
                boolean hasDisconnect=neutralHigh;
                if(route.path().stream().filter(e->e.behavior().endsWith("/invert_signal")).count()%2!=0) neutralHigh=!neutralHigh;
                boolean drive=route.path().getLast().medium().equals("kinetic") && circuit.node(route.actuator()).locomotion();
                if(drive && !hasDisconnect && node.kind()!=STEERING_WHEEL)
                    issues.add(node.id()+": no established power-disconnect state");
                if(route.path().stream().anyMatch(e->e.behavior().endsWith("/reverse_when_powered")))
                    issues.add(node.id()+": reversing a running source does not establish neutral");
                disconnected|=neutralHigh; ordinary|=!neutralHigh; propulsion|=drive||brake;
            }
            if(disconnected && ordinary) { issues.add(node.id()+": conflicting neutral states across affected actuators"); continue; }
            double maximum=node.kind()==KEY ? 1 : node.kind()==THROTTLE ? 15
                    : ((Number)node.facts().getOrDefault("angle_limit",0)).doubleValue();
            if(maximum<=0) { issues.add(node.id()+": unavailable control limits"); continue; }
            double neutral=disconnected ? maximum : 0;
            // Releasing a typewriter key must not restart a powered transmission during handoff.
            if(node.kind()==KEY && disconnected) issues.add(node.id()+": hold-to-stop circuit needs an independent persistent brake");
            inputs.add(new Input(node.id(),node.kind(),neutral,node.kind()==STEERING_WHEEL ? -maximum:0,maximum,
                    routes.stream().map(ControlCircuit.Route::actuator).distinct().toList(),propulsion));
        }
        if(inputs.isEmpty()) issues.add("no usable controls");
        else if(inputs.stream().noneMatch(Input::propulsion)) issues.add("no controllable propulsion or braking path");
        return new VehicleControlPlan(inputs,issues);
    }
    public boolean usable() { return limitations.isEmpty() && !inputs.isEmpty(); }
}
