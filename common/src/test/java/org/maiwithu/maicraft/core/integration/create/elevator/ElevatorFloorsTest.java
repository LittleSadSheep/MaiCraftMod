package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.List;
import java.util.UUID;

public final class ElevatorFloorsTest {
    public static void main(String[] args) {
        var floors=List.of(new ElevatorFloors.Floor("floor:10",10,"1","Lobby",true),
                new ElevatorFloors.Floor("floor:22",22,"2","Machines",true),
                new ElevatorFloors.Floor("floor:37",37,"3","Roof",true),
                new ElevatorFloors.Floor("floor:50",50,"4","Outside rope range",false));
        var elevator=new ElevatorFloors.Elevator(UUID.randomUUID(),15,2,10,floors);
        check(ElevatorFloors.select(elevator,"top").contactY()==37,"top means the highest served native floor, not the second landmark");
        check(ElevatorFloors.select(elevator,"next_up").contactY()==22,"next floor uses the player's source floor");
        check(ElevatorFloors.select(elevator,"Roof").contactY()==37,"native long names can select a floor");
        check(ElevatorFloors.select(elevator,"floor:50")==null,"unserved floors cannot be selected");
        check(ElevatorFloors.select(new ElevatorFloors.Elevator(elevator.id(),15,2,null,floors),"next_up")==null,
                "unknown player floor cannot be replaced by the cabin's current height");
        check(ElevatorFloors.describe(new ElevatorFloors.Elevator(elevator.id(),15,2,null,List.of())).get("floor_list_state").equals("needs_sync"),
                "an unsynchronized list is not a zero-floor elevator");
        System.out.println("ElevatorFloorsTest: passed");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
