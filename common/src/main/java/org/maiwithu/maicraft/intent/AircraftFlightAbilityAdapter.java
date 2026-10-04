package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftFlightTaskRecord;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftProfile;

/** LLM 只提供飞机、操纵声明和目的地；飞控任务在 Mod 内持续执行，不能接收逐帧点击脚本。 */
final class AircraftFlightAbilityAdapter {
    static final String ABILITY="maicraft:fly_vehicle";
    private AircraftFlightAbilityAdapter() {}
    static void validate(Goal goal) {
        JsonObject p=goal.parameters();
        for(String key:p.keySet())if(!Set.of("structure_id","operation","profile","direction","distance","cruise_altitude").contains(key))
            throw new IllegalArgumentException("unknown fly_vehicle parameter: "+key);
        String id=text(p,"structure_id",null);if(id==null)throw new IllegalArgumentException("structure_id is required");
        UUID.fromString(id);String operation=text(p,"operation","fly");
        if(!Set.of("inspect","configure","fly").contains(operation))throw new IllegalArgumentException("flight operation must be inspect, configure or fly");
        if(p.has("profile")) {
            if(operation.equals("inspect")||!p.get("profile").isJsonObject())throw new IllegalArgumentException("inspect cannot change an aircraft profile");
            AircraftProfile.parse(p.getAsJsonObject("profile"));
        }
        if(operation.equals("configure")&&!p.has("profile"))throw new IllegalArgumentException("configure requires a complete aircraft profile");
        if(!operation.equals("fly")) {
            if(goal.target()!=null||p.has("direction")||p.has("distance")||p.has("cruise_altitude"))throw new IllegalArgumentException("flight profile operations do not move the aircraft");
            return;
        }
        String direction=text(p,"direction",null);
        if((goal.target()==null)==(direction==null))throw new IllegalArgumentException("fly requires exactly one destination target or direction");
        if(goal.target()!=null&&!Set.of("coordinates","landmark","area").contains(goal.target().kind()))throw new IllegalArgumentException("flight target must be coordinates or a remembered place");
        if(goal.target()!=null&&goal.target().kind().equals("coordinates")&&goal.target().position()==null)throw new IllegalArgumentException("flight coordinates require a world position");
        if(direction!=null&&!Set.of("north","south","east","west","forward","backward","left","right").contains(direction))throw new IllegalArgumentException("unsupported flight direction");
        if(p.has("distance")&&direction==null)throw new IllegalArgumentException("distance is only used with direction");
        double distance=number(p,"distance",256);if(distance<16||distance>8192)throw new IllegalArgumentException("flight direction distance must be 16..8192 blocks");
        number(p,"cruise_altitude",0);
    }
    static IntentAction adapt(Goal goal,LocalPlayer player,IntentRuntime runtime) {
        validate(goal);JsonObject p=goal.parameters();Vec3 destination=null;
        if(goal.target()!=null) {
            Goal.WorldPosition at;
            if(goal.target().kind().equals("coordinates"))at=goal.target().position();
            else {var place=runtime.landmark(goal.target().label());at=place==null?null:place.position();}
            if(at==null||at.dimension()!=null&&!at.dimension().equals(player.level().dimension().location().toString()))
                throw new IllegalArgumentException("flight destination must resolve in the current dimension");
            destination=new Vec3(at.x()+.5,at.y(),at.z()+.5);
        }
        return new IntentAction.Native(new AircraftFlightTaskRecord("aircraft-"+UUID.randomUUID(),player.level().getGameTime()+20*60*60,
                UUID.fromString(text(p,"structure_id",null)),text(p,"operation","fly"),p.has("profile")?AircraftProfile.parse(p.getAsJsonObject("profile")):null,
                destination,text(p,"direction",null),number(p,"distance",256),p.has("cruise_altitude")?number(p,"cruise_altitude",0):null));
    }
    static JsonObject contract() {
        var out=new JsonObject();out.addProperty("summary","Mod 内持续飞控：读取本机操纵声明、原生入座与无线打字机接管，再执行起飞、巡航、局部避障、进近和着陆。LLM 只给坐标或方向，配置声明不等于真实飞行验证。");
        var targets=new JsonArray();List.of("coordinates","landmark","area").forEach(targets::add);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());var fields=new JsonObject();
        field(fields,"structure_id","string","Observed Sable structure UUID; reused flight profiles are isolated by world, dimension and UUID.");
        field(fields,"operation","string","fly (default), configure or inspect. configure saves an explicit profile; inspect reads it and current support/ownership telemetry, including when no profile is registered. Profile operations omit target and flight destination fields.");
        field(fields,"profile","object","Complete optional declaration, required for configure or first flight: kind=fixed_wing|airship; integer local seat_position and typewriter_position; forward=north|south|east|west (default south); keys maps power, brake, pitch_up/down, bank_left/right, yaw_left/right, lift to bound keyboard names. Power is hold-to-run and released-to-disconnect; lift is hold-to-enable buoyancy. Optional takeoff_speed/cruise_speed in blocks/s, max_bank_degrees, climb_pitch_degrees, approach_pitch_degrees, climb_rate/descent_rate. Native binding identity and seated reach are checked during execution. Saved mapping is a design declaration, not proof of controllability.");
        field(fields,"direction","string","Alternative to target: north/south/east/west or forward/backward/left/right relative to this aircraft's observed departure heading. Frozen after boarding; not the pilot camera direction.");
        field(fields,"distance","number","With direction only, 16..8192 blocks, default 256. The Mod completes the flight leg without LLM key polling.");
        field(fields,"cruise_altitude","number","Optional finite world Y for nominal cruise; default 32 above departure/destination height. Obstacle detours and landing use observed local paths.");
        out.add("parameters",fields);
        out.addProperty("execution_boundary","Only native seat, typewriter and keyboard protocols change the world; no position, velocity, force or inventory writes. Landing requires observed support and stopped motion after key release. Unknown terrain is not clear. Cancellation requests nearby landing; human takeover releases held inputs. Flight ends near a selected landing site; travel must separately finish the final ground leg.");
        return out;
    }
    private static String text(JsonObject p,String key,String fallback){if(!p.has(key))return fallback;if(!p.get(key).isJsonPrimitive()||!p.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException(key+" requires text");return p.get(key).getAsString();}
    private static double number(JsonObject p,String key,double fallback){if(!p.has(key))return fallback;if(!p.get(key).isJsonPrimitive()||!p.getAsJsonPrimitive(key).isNumber()||!Double.isFinite(p.get(key).getAsDouble()))throw new IllegalArgumentException(key+" requires a finite number");return p.get(key).getAsDouble();}
    private static void field(JsonObject fields,String name,String type,String description){var field=new JsonObject();field.addProperty("type",type);field.addProperty("description",description);fields.add(name,field);}
}
