package org.maiwithu.maicraft.core.integration.physics.flight;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.physics.TypewriterKeyInput;
import org.maiwithu.maicraft.core.tools.SemanticParameters;

/** 飞机设计者登记本机的座位、打字机、机头朝向和原生键位；保存映射不等于已经通过飞行验收。 */
public record AircraftProfile(BlockPos seat,BlockPos typewriter,Direction forward,FlightEnvelope envelope,
                              Map<FlightKeyMixer.Role,Integer> keys) {
    public AircraftProfile {seat=seat.immutable();typewriter=typewriter.immutable();keys=Map.copyOf(keys);}
    public Vec3 forwardVector(){return new Vec3(forward.getStepX(),0,forward.getStepZ());}
    public static AircraftProfile parse(JsonObject p) {
        // 飞行档案写错字段名时连同合法键报出，模型改正 profile 后即可重新提交起飞。
        Set<String> profileFields=Set.of("kind","seat_position","typewriter_position","forward","keys","takeoff_speed",
                "cruise_speed","max_bank_degrees","climb_pitch_degrees","approach_pitch_degrees","climb_rate","descent_rate");
        for(String key:p.keySet())if(!profileFields.contains(key))
            throw new IllegalArgumentException("unknown aircraft profile field: "+key+SemanticParameters.acceptedKeys(profileFields));
        FlightEnvelope.Kind kind=FlightEnvelope.Kind.valueOf(text(p,"kind").toUpperCase(Locale.ROOT));
        var defaults=kind==FlightEnvelope.Kind.FIXED_WING?FlightEnvelope.fixedWing():FlightEnvelope.airship();
        Direction forward=Direction.byName(p.has("forward")?text(p,"forward"):"south");
        if(forward==null||!forward.getAxis().isHorizontal())throw new IllegalArgumentException("aircraft forward must be north/south/east/west in its local frame");
        var envelope=new FlightEnvelope(kind,number(p,"takeoff_speed",defaults.takeoffSpeed()),number(p,"cruise_speed",defaults.cruiseSpeed()),
                Math.toRadians(number(p,"max_bank_degrees",Math.toDegrees(defaults.maximumBank()))),
                Math.toRadians(number(p,"climb_pitch_degrees",Math.toDegrees(defaults.climbPitch()))),
                Math.toRadians(number(p,"approach_pitch_degrees",Math.toDegrees(defaults.approachPitch()))),
                number(p,"climb_rate",defaults.climbRate()),number(p,"descent_rate",defaults.descentRate()));
        if(!p.has("keys")||!p.get("keys").isJsonObject())throw new IllegalArgumentException("aircraft keys are required");
        var keys=new EnumMap<FlightKeyMixer.Role,Integer>(FlightKeyMixer.Role.class);
        for(var entry:p.getAsJsonObject("keys").entrySet()) {
            if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("aircraft keys require keyboard names");
            keys.put(FlightKeyMixer.Role.valueOf(entry.getKey().toUpperCase(Locale.ROOT)),TypewriterKeyInput.code(entry.getValue().getAsString()));
        }
        if(!keys.containsKey(FlightKeyMixer.Role.POWER))throw new IllegalArgumentException("aircraft power key is required");
        return new AircraftProfile(position(p,"seat_position"),position(p,"typewriter_position"),forward,envelope,keys);
    }
    public JsonObject json() {
        var p=new JsonObject();p.addProperty("kind",envelope.kind().name().toLowerCase(Locale.ROOT));
        p.add("seat_position",position(seat));p.add("typewriter_position",position(typewriter));p.addProperty("forward",forward.getName());
        p.addProperty("takeoff_speed",envelope.takeoffSpeed());p.addProperty("cruise_speed",envelope.cruiseSpeed());
        p.addProperty("max_bank_degrees",Math.toDegrees(envelope.maximumBank()));p.addProperty("climb_pitch_degrees",Math.toDegrees(envelope.climbPitch()));
        p.addProperty("approach_pitch_degrees",Math.toDegrees(envelope.approachPitch()));p.addProperty("climb_rate",envelope.climbRate());p.addProperty("descent_rate",envelope.descentRate());
        var map=new JsonObject();keys.forEach((role,key)->map.addProperty(role.name().toLowerCase(Locale.ROOT),TypewriterKeyInput.name(key)));p.add("keys",map);return p;
    }
    private static BlockPos position(JsonObject p,String field) {
        if(!p.has(field)||!p.get(field).isJsonObject())throw new IllegalArgumentException(field+" requires integer local x/y/z");
        var value=p.getAsJsonObject(field);if(!value.keySet().equals(Set.of("x","y","z")))throw new IllegalArgumentException(field+" requires exactly x/y/z");
        int[] axes=new int[3];int i=0;
        for(String axis:List.of("x","y","z")) {
            if(!value.get(axis).isJsonPrimitive()||!value.getAsJsonPrimitive(axis).isNumber())throw new IllegalArgumentException("local position must use integers");
            try {axes[i]=value.get(axis).getAsBigDecimal().intValueExact();}
            catch(ArithmeticException invalid){throw new IllegalArgumentException("local position must use integers",invalid);}
            if(Math.abs((long)axes[i++])>256)throw new IllegalArgumentException("local position outside -256..256");
        }
        return new BlockPos(axes[0],axes[1],axes[2]);
    }
    private static JsonObject position(BlockPos pos){var p=new JsonObject();p.addProperty("x",pos.getX());p.addProperty("y",pos.getY());p.addProperty("z",pos.getZ());return p;}
    private static String text(JsonObject p,String key){if(!p.has(key)||!p.get(key).isJsonPrimitive()||!p.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException(key+" requires text");return p.get(key).getAsString();}
    private static double number(JsonObject p,String key,double fallback) {
        if(!p.has(key))return fallback;
        if(!p.get(key).isJsonPrimitive()||!p.getAsJsonPrimitive(key).isNumber()||!Double.isFinite(p.get(key).getAsDouble()))throw new IllegalArgumentException(key+" requires a finite number");
        return p.get(key).getAsDouble();
    }
}
