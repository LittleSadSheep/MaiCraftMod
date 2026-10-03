package org.maiwithu.maicraft.core.integration.physics.balance;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/** 停车时先声明运行/停车刹车工况，再修改隔离副本；实际轮胎信号和已采到的力都保持原样。 */
public final class PhysicsWheelScenarios {
    private PhysicsWheelScenarios() {}
    public static Map<String,PhysicsWheel.Brakes> parse(JsonObject request) {
        if(!request.has("wheel_brakes"))return Map.of();
        if(!request.get("wheel_brakes").isJsonObject())throw new IllegalArgumentException("wheel_brakes 必须按观察到的轮胎载荷编号声明工况");
        var entries=request.getAsJsonObject("wheel_brakes");
        if(entries.size()>64)throw new IllegalArgumentException("单次最多声明 64 个轮胎的刹车工况");
        var result=new LinkedHashMap<String,PhysicsWheel.Brakes>();
        for(var entry:entries.entrySet()) {
            if(entry.getKey().isBlank()||!entry.getValue().isJsonObject())throw new IllegalArgumentException("轮胎刹车需要来源编号及 {running,stopped}");
            var value=entry.getValue().getAsJsonObject();
            if(!value.keySet().equals(Set.of("running","stopped")))throw new IllegalArgumentException("刹车工况必须同时声明 running 和 stopped");
            result.put(entry.getKey(),new PhysicsWheel.Brakes(number(value,"running"),number(value,"stopped")));
        }
        return Map.copyOf(result);
    }
    private static double number(JsonObject value,String key) {
        if(!value.get(key).isJsonPrimitive()||!value.getAsJsonPrimitive(key).isNumber())throw new IllegalArgumentException("刹车工况应为 0..1 数字");
        return value.get(key).getAsDouble();
    }
    public static PhysicsBody apply(PhysicsBody source,Map<String,PhysicsWheel.Brakes> settings) {
        if(settings.isEmpty())return source;
        var remaining=new LinkedHashMap<>(settings);var loads=new ArrayList<PhysicsBody.Load>();
        for(var load:source.loads()) {
            var brakes=remaining.remove(load.id());
            if(brakes==null){loads.add(load);continue;}
            if(load.wheel()==null||load.wheel().referenceRpm()==null)
                throw new IllegalArgumentException("刹车工况没有对应的完整轮胎预测来源: "+load.id());
            loads.add(new PhysicsBody.Load(load.id(),load.group(),load.point(),load.force(),load.torque(),load.frame(),
                    load.propulsion(),load.responseSeconds(),load.airflow(),load.aerodynamics(),load.wheel().withBrakes(brakes)));
        }
        if(!remaining.isEmpty())throw new IllegalArgumentException("刹车工况引用了未观察到的轮胎: "+remaining.keySet());
        return new PhysicsBody(source.structureId(),source.dimension(),source.tick(),source.mass(),source.center(),source.inertia(),source.rotation(),
                source.position(),source.velocity(),source.angularVelocity(),source.gravity(),loads,source.unknowns());
    }
}
