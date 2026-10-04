package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.core.tools.work.BoardStructureTool;
import org.maiwithu.maicraft.task.TaskDispatch;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicReference;

public final class ShipTravelContractTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var f = new InteractionWorldTestHarness()) {
            UUID id = UUID.randomUUID();
            JsonObject parameters = new JsonObject(); parameters.addProperty("structure_id",id.toString());
            for (String mode : List.of("auto","jetpack")) {
                parameters.addProperty("transport_mode",mode);
                Goal goal = goal(parameters); SemanticGoalContract.validate(goal,Set.of("maicraft:travel"));
                var action = AbilityAdapter.adapt(goal,f.player,null);
                check(action instanceof IntentAction.Tool tool && tool.toolName().equals("board_structure"),
                        "observed UUID must compile into boarding instead of a static goto");
                var tool = (IntentAction.Tool) action;
                var record = new AtomicReference<BoardStructureTaskRecord>();
                TaskDispatch.captureNext(value -> record.set((BoardStructureTaskRecord)value),
                        () -> new BoardStructureTool().onGameCall("ship-test",tool.arguments(),f.player,
                                ignored -> { throw new AssertionError("unexpected direct reply"); }));
                check(record.get().structureId.equals(id), "native task must retain the same vessel identity");
            }
            parameters.addProperty("transport_mode","ground"); reject(parameters,f);
            parameters.addProperty("transport_mode","jetpack");
            // 飞艇座位坐标必须贯穿语义、工具与持久任务；小数或脱离船体身份的座位不能被忽略。
            parameters.add("seat_position",JsonParser.parseString("{\"x\":-1,\"y\":-5,\"z\":-3}"));
            SemanticGoalContract.validate(goal(parameters),Set.of("maicraft:travel"));
            var seated=(IntentAction.Tool)AbilityAdapter.adapt(goal(parameters),f.player,null);
            var seatRecord=new AtomicReference<BoardStructureTaskRecord>();
            TaskDispatch.captureNext(value->seatRecord.set((BoardStructureTaskRecord)value),
                    ()->new BoardStructureTool().onGameCall("seat-test",seated.arguments(),f.player,ignored->{}));
            check(seatRecord.get().seatOffset.equals(new BlockPos(-1,-5,-3)),"seat offset was lost before native execution");
            parameters.getAsJsonObject("seat_position").addProperty("y",-5.5);reject(parameters,f);
            parameters.remove("seat_position");
            parameters.add("destination",JsonParser.parseString("{\"x\":1,\"y\":2,\"z\":3}")); reject(parameters,f);
            parameters.remove("destination"); parameters.addProperty("structure_id","not-an-observed-uuid"); reject(parameters,f);
        }
        System.out.println("ShipTravelContractTest: passed");
    }
    private static Goal goal(JsonObject p) { return new Goal("maicraft:travel","board the observed vessel",null,p.toString(),"{}",List.of(),List.of()); }
    private static void reject(JsonObject p, InteractionWorldTestHarness f) {
        try { AbilityAdapter.adapt(goal(p),f.player,null); throw new AssertionError("ambiguous or invalid boarding request accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean value,String reason) { if (!value) throw new AssertionError(reason); }
}
