package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.HashSet;
import java.util.Map;

public final class ControlInspectionReportTest {
    public static void main(String[] args) {
        var circuit=new ControlCircuit();
        circuit.add(new ControlCircuit.Node("button",ControlCircuit.Kind.OTHER,Map.of("block_id","minecraft:stone_button")));
        circuit.add(new ControlCircuit.Node("radio",ControlCircuit.Kind.TRANSMITTER,Map.of()));
        circuit.add(new ControlCircuit.Node("terrain",ControlCircuit.Kind.OTHER,Map.of()));
        circuit.connect(new ControlCircuit.Edge("button","radio","redstone","observed_support",true));
        var observation=new MachineControlInspection.Observation(null,Map.of(),circuit,null,true,4.5);
        var report=observation.report(); var ids=new HashSet<String>();
        report.getAsJsonArray("components").forEach(n->ids.add(n.getAsJsonObject().get("id").getAsString()));
        for(var edge:report.getAsJsonArray("connections")) {
            var row=edge.getAsJsonObject();
            if(!ids.contains(row.get("from").getAsString()) || !ids.contains(row.get("to").getAsString()))
                throw new AssertionError("reported connection references an omitted component");
        }
        if(ids.contains("terrain")) throw new AssertionError("unrelated terrain obscures the control report");
        if(!report.get("read_only").getAsBoolean()) throw new AssertionError("inspection cannot claim execution");
        System.out.println("ControlInspectionReportTest: passed");
    }
}
