package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftFlightTask;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftProfile;

/** 坐标飞行与方向飞行二选一，配置不会偷偷开动力，局部座位不能接受含糊的小数坐标。 */
public final class AircraftFlightContractTest {
    public static void run() {
        String id=UUID.randomUUID().toString();
        var target=new Goal.SemanticTarget("coordinates",null,new Goal.WorldPosition(100,70,200,"minecraft:overworld"),null);
        String profile="{\"kind\":\"fixed_wing\",\"seat_position\":{\"x\":0,\"y\":1,\"z\":0},\"typewriter_position\":{\"x\":1,\"y\":1,\"z\":1},\"keys\":{\"power\":\"w\",\"pitch_up\":\"up\",\"pitch_down\":\"down\"}}";
        valid(goal(target,"{\"structure_id\":\""+id+"\"}"));
        valid(goal(null,"{\"structure_id\":\""+id+"\",\"direction\":\"forward\",\"distance\":100}"));
        valid(goal(null,"{\"structure_id\":\""+id+"\",\"operation\":\"configure\",\"profile\":"+profile+"}"));
        for(String invalid:List.of("{}","{\"structure_id\":\""+id+"\"}",
                "{\"structure_id\":\""+id+"\",\"operation\":\"configure\"}",
                "{\"structure_id\":\""+id+"\",\"operation\":\"inspect\",\"profile\":"+profile+"}",
                "{\"structure_id\":\""+id+"\",\"direction\":\"forward\",\"distance\":0}"))reject(goal(null,invalid));
        reject(goal(target,"{\"structure_id\":\""+id+"\",\"direction\":\"north\"}"));
        var parsed=AircraftProfile.parse(JsonParser.parseString(profile).getAsJsonObject());
        check(AircraftProfile.parse(parsed.json()).keys().equals(parsed.keys()),"保存与读取必须保留相同键位含义");
        try {AircraftProfile.parse(JsonParser.parseString(profile.replace("\"x\":0","\"x\":0.5")).getAsJsonObject());throw new AssertionError("小数座位被接单");}
        catch(IllegalArgumentException expected) {}
        check(AircraftFlightTask.direction("right",new Vec3(0,0,1)).equals(new Vec3(-1,0,0)),"飞机朝南时右转应为西，不能按相机或相反坐标系判断");
        System.out.println("AircraftFlightContractTest: passed");
    }
    private static Goal goal(Goal.SemanticTarget target,String parameters){return new Goal(AircraftFlightAbilityAdapter.ABILITY,"自动飞行",target,parameters,"{}",List.of(),List.of());}
    private static void valid(Goal goal){SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES);}
    private static void reject(Goal goal){try{valid(goal);}catch(IllegalArgumentException expected){return;}throw new AssertionError("非法飞控目标被接单");}
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
}
