package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloors;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloorTaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/** 处理“坐电梯去某层”：先读已加载电梯与楼层，需要时走近同步楼层，让调用者选层后再真正乘坐。 */
final class ElevatorTravelIntent {
    static boolean applies(Goal goal) {
        // 明确填了电梯编号／楼层，或只要求电梯而没给别的目的地时，使用这套选层流程。
        var p=goal.parameters();
        if(p.has("elevator_id") || p.has("elevator_floor")) return true;
        return "elevator".equals(string(p,"transport_mode")) && !p.has("destination") && !p.has("block") && !p.has("block_id")
                && !p.has("semantic_target") && !p.has("biome_id") && !p.has("biome_tag")
                && (goal.target()==null || "nearest".equals(goal.target().kind()));
    }
    static IntentAction adapt(Goal goal,LocalPlayer player) {
        validate(goal);
        return choose(goal,ElevatorFloors.observe(player),player==null ? 0 : player.level().getGameTime());
    }
    private static void validate(Goal goal) {
        var p=goal.parameters();
        var mode=TransportMode.parse(string(p,"transport_mode"));
        if(mode!=TransportMode.AUTO && mode!=TransportMode.ELEVATOR)
            throw new IllegalArgumentException("elevator_floor uses auto or elevator transport");
        if(goal.target()!=null && !"nearest".equals(goal.target().kind()) || p.has("destination") || p.has("structure_id")
                || p.has("block_id") || p.has("block") || p.has("semantic_target") || p.has("biome_id") || p.has("biome_tag") || p.has("direction"))
            throw new IllegalArgumentException("elevator floor selection cannot be combined with another destination");
        if(p.has("exact") && p.get("exact").getAsBoolean()) throw new IllegalArgumentException("an elevator floor is a region, not one exact cell");
    }
    static IntentAction choose(Goal goal,List<ElevatorFloors.Elevator> elevators,long tick) {
        // 没给电梯编号时取观察列表中的第一台；先拿到它的同步楼层，再决定能否选到指定目标层。
        validate(goal);
        String id=string(goal.parameters(),"elevator_id");
        UUID identity=id==null ? null : UUID.fromString(id);
        var elevator=elevators.stream().filter(e->identity==null || identity.equals(e.id())).findFirst().orElse(null);
        if(elevator==null) return decision(goal,"No matching loaded elevator is observed. Approach the elevator area before selecting a floor.",elevators);
        String floor=string(goal.parameters(),"elevator_floor");
        boolean ask=floor==null || floor.equals("ask");
        if(elevator.floors().isEmpty() || ask && !elevator.readyForDecision()) {
            // 还不知道楼层不等于没有楼层：先创建观察任务，成功后重新判断原移动目标，不把观察成功当成乘梯成功。
            var observation=new ElevatorFloorTaskRecord("elevator-floor-observation",tick+1200,elevator.id(),null,ask);
            return new IntentAction.Native(observation,true);
        }
        if(ask) return decision(goal,"Elevator floors are available. Choose elevator_id and elevator_floor using retry details.parameters, or cancel.",List.of(elevator));
        var selected=ElevatorFloors.select(elevator,floor);
        if(selected==null) return decision(goal,"That floor is unavailable or ambiguous; choose a served floor from the synchronized list.",List.of(elevator));
        return new IntentAction.Native(new ElevatorFloorTaskRecord("elevator-selected-floor",tick+3600,elevator.id(),selected.contactY(),false));
    }
    private static IntentAction.Decision decision(Goal goal,String question,List<ElevatorFloors.Elevator> elevators) {
        var context=new JsonObject(); context.addProperty("ability",goal.ability()); context.addProperty("outcome",goal.outcome());
        context.add("elevators",new Gson().toJsonTree(elevators.stream().limit(4).map(ElevatorFloors::describe).toList()));
        return new IntentAction.Decision(new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(),question,
                List.of(new IntentTaskRecord.DecisionOption("retry","Choose an observed elevator and floor with details.parameters."),
                        new IntentTaskRecord.DecisionOption("cancel","Cancel elevator travel.")),context.toString()));
    }
    private static String string(JsonObject value,String key) {
        if(!value.has(key) || value.get(key).isJsonNull()) return null;
        if(!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString() || value.get(key).getAsString().isBlank())
            throw new IllegalArgumentException(key+" must be a nonempty string");
        return value.get(key).getAsString();
    }
}
