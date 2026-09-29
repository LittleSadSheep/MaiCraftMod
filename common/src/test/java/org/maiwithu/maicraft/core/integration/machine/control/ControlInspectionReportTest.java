package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.HashSet;
import java.util.Map;
import java.util.List;
import com.google.gson.Gson;
import org.maiwithu.maicraft.intent.SemanticResultView;

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
        publicGraphPreservesReferences();
        System.out.println("ControlInspectionReportTest: passed");
    }

    private static void publicGraphPreservesReferences() {
        // 同一机器的两个坐标节点必须保持不同；原生图经过 Map、JSON 和子任务文字回执后仍可沿连线追踪。
        String control = "505,80,109", actuator = "507,80,104";
        var circuit = new ControlCircuit();
        circuit.add(new ControlCircuit.Node(control, ControlCircuit.Kind.KEY,
                Map.of("storage_position", List.of(505, 80, 109))));
        circuit.add(new ControlCircuit.Node(actuator, ControlCircuit.Kind.JOINT,
                Map.of("storage_position", List.of(507, 80, 104))));
        circuit.connect(new ControlCircuit.Edge(control, actuator, "kinetic", "native_connection", true));
        circuit.unknown("unobserved branch at 509,80,104");
        var report = new MachineControlInspection.Observation(null, Map.of(), circuit, null, false, 4.5).report();
        var gson = new Gson();
        for (Object source : List.of(report, gson.fromJson(report, Map.class))) {
            var envelope = Map.of("control_analysis", source, "position", List.of(1, 2, 3));
            var direct = gson.toJsonTree(SemanticResultView.data(envelope)).getAsJsonObject();
            var nested = gson.toJsonTree(SemanticResultView.data(Map.of("child_data", gson.toJson(envelope))))
                    .getAsJsonObject().getAsJsonObject("child_data");
            for (var result : List.of(direct, nested)) {
                if (!report.equals(result.getAsJsonObject("control_analysis")))
                    throw new AssertionError("public control evidence lost native node identities, routes, or uncertainty");
                if (result.has("position")) throw new AssertionError("unrelated action positions keep their existing boundary");
            }
        }
    }
}
