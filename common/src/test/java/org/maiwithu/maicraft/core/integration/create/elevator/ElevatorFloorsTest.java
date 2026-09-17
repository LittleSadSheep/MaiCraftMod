package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 检查楼层名称与相对选层使用真实已服务楼层，来源层未知时不能猜下一层，空列表应表示尚待同步。
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
        // 相对参考点的高度事实：不给出它，调用方只能靠楼层标签猜高低（"低层"就可能被理解成地下室）
        @SuppressWarnings("unchecked")
        var describedFloors=(List<Map<String,Object>>)ElevatorFloors.describe(elevator,22.0).get("floors");
        check(describedFloors.size()==4 && describedFloors.getFirst().get("relative_y").equals(-12.0)
                        && describedFloors.getFirst().get("above_reference").equals(false),
                "floors below the reference must be reported as below instead of guessed from labels");
        check(describedFloors.get(2).get("relative_y").equals(15.0)
                        && describedFloors.get(2).get("above_reference").equals(true),
                "floors above the reference must be reported as above");
        check(!ElevatorFloors.describe(elevator).containsKey("relative_to"),
                "without a reference point the fact is absent rather than guessed");
        System.out.println("ElevatorFloorsTest: passed");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
