package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.ArrayList;
import java.util.Map;

public final class FlightTraceTest {
    public static void main(String[] args) {
        var samples = new ArrayList<Map<String,Object>>();
        for (long tick=1;tick<=48;tick++) samples.add(Map.of("tick",tick,"phase","jetpack_plan","position","same",
                "health",20,"native_client_evidence",Map.of("native_up",false,"on_ground",true,"verbose_unused_field","metadata")));
        var compact = JetpackFlightSession.compactTrace(samples);
        check(compact.size()==1 && compact.getFirst().get("samples").equals(48),"idle planning must collapse instead of repeating native dictionaries");
        check(compact.getFirst().get("first_tick").equals(1L) && compact.getFirst().get("last_tick").equals(48L),"summary must retain the observed time span");
        check(!compact.getFirst().containsKey("native_client_evidence"),"full native evidence already has a single top-level location");
        samples.add(Map.of("tick",49L,"phase","jetpack_fly","position","moved","health",19));
        compact = JetpackFlightSession.compactTrace(samples);
        check(compact.size()==2 && compact.getLast().get("health").equals(19),"phase, position and health changes must remain visible");
        for (long tick=50;tick<100;tick++) samples.add(Map.of("tick",tick,"position",Long.toString(tick)));
        compact = JetpackFlightSession.compactTrace(samples);
        check(compact.size()==8 && compact.getLast().get("last_tick").equals(99L),"moving trace must retain a bounded recent history");
        System.out.println("FlightTraceTest: passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
