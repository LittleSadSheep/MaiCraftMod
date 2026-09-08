package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloors;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloorTaskRecord;

public final class ElevatorTravelIntentTest {
    public static void main(String[] args) {
        UUID id=UUID.randomUUID();
        var floors=List.of(new ElevatorFloors.Floor("floor:10",10,"1","Lobby",true),
                new ElevatorFloors.Floor("floor:22",22,"2","Machines",true),new ElevatorFloors.Floor("floor:37",37,"3","Roof",true));
        var nearby=new ElevatorFloors.Elevator(id,2,2,10,floors);
        Goal ask=goal("{\"transport_mode\":\"elevator\"}");
        check(ElevatorTravelIntent.applies(ask),"unlocated elevator travel enters floor discovery");
        var pending=(IntentAction.Native)ElevatorTravelIntent.choose(ask,List.of(new ElevatorFloors.Elevator(id,30,20,null,List.of())),0);
        var observation=(ElevatorFloorTaskRecord)pending.record();
        check(pending.reobserveAfterSuccess() && observation.approach && observation.floor==null,
                "reaching the elevator and synchronizing floors must re-enter semantic decision, not complete travel");
        var decision=(IntentAction.Decision)ElevatorTravelIntent.choose(ask,List.of(nearby),20);
        check(decision.snapshot().context().getAsJsonArray("elevators").get(0).getAsJsonObject().get("elevator_id").getAsString().equals(id.toString()),
                "the LLM receives synchronized floors tied to the actual cabin UUID");
        var chosen=(IntentAction.Native)ElevatorTravelIntent.choose(goal("{\"transport_mode\":\"elevator\",\"elevator_floor\":\"top\"}"),List.of(nearby),30);
        var ride=(ElevatorFloorTaskRecord)chosen.record();
        check(!chosen.reobserveAfterSuccess() && ride.floor==37 && ride.elevatorId.equals(id),"top selects the highest served native floor without coordinates");
        check(ElevatorTravelIntent.choose(goal("{\"elevator_floor\":\"unknown\"}"),List.of(nearby),0) instanceof IntentAction.Decision,
                "an unknown name returns a decision instead of a guessed height");
        try {
            ElevatorTravelIntent.choose(goal("{\"elevator_floor\":\"top\",\"destination\":{\"x\":1,\"z\":2}}"),List.of(nearby),0);
            throw new AssertionError("mixed floor and coordinate destination accepted");
        } catch(IllegalArgumentException expected) { }
        System.out.println("ElevatorTravelIntentTest: passed");
    }
    private static Goal goal(String parameters) { return new Goal("maicraft:travel","Go to the chosen elevator floor",null,parameters,"{}",List.of(),List.of()); }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
