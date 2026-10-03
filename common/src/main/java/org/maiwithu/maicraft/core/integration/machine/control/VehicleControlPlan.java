package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlCircuit.Kind.*;

/** 根据已连接的机构推导控制范围和中性信号，不依赖按键名称。 */
public record VehicleControlPlan(List<Input> inputs, List<String> limitations) {
    public record Input(String id,ControlCircuit.Kind kind,double neutral,double minimum,double maximum,
                        List<String> actuators,boolean propulsion) {
        public Input { actuators=List.copyOf(actuators); }
        public double clamp(double value) { return Math.clamp(value,minimum,maximum); }
        public List<Double> probes() {
            if(kind==KEY) return List.of(1-neutral);
            var values=new ArrayList<Double>();
            // 满刹车附近两档可能还不足以带动重车；逐级试探，每档之间停稳，已有响应的方向不再加大输入。
            // 方向盘经比较器量化后，小角度可能还没有可用转向；两边分别逐档尝试，并保留原生角度上限。
            var steps=kind==STEERING_WHEEL?List.of(Math.min(15,maximum),Math.min(30,maximum),Math.min(60,maximum),Math.min(120,maximum),maximum)
                    :kind==THROTTLE&&propulsion?List.of(2.0,4.0,8.0,15.0):List.of(2.0);
            for(double step:steps) {
                if(neutral-step>=minimum&&!values.contains(neutral-step)) values.add(neutral-step);
                if(neutral+step<=maximum&&!values.contains(neutral+step)) values.add(neutral+step);
            }
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
            // 交接过程中释放类似打字机的按键时，不能重新启动已通电的传动机构。
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
