package org.maiwithu.maicraft.core.integration.physics.balance;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** 计划阶段明确船体、试算工况及补丁，分析不会被隐式升级为真实施工或启动推进器。 */
public record PhysicsBalanceParameters(UUID structureId, String operation, double rpm, String balloonFill,
                                       int maxBallastBlocks, PhysicsSimulation.Limits limits,
                                       Map<String,Double> controls, JsonObject request) {
    public PhysicsBalanceParameters { controls=Map.copyOf(controls); request=request.deepCopy(); }
    @Override public JsonObject request() { return request.deepCopy(); }
    public static PhysicsBalanceParameters parse(JsonObject args) {
        Set<String> accepted=Set.of("structure_id","operation","reference_rpm","balloon_fill","max_ballast_blocks",
                "duration_seconds","max_tilt_degrees","max_vertical_acceleration","max_angular_acceleration",
                "perturbation_degrees","controls","edits","ballast_candidates");
        for(String key:args.keySet()) if(!accepted.contains(key)) throw new IllegalArgumentException("未知配平参数: "+key);
        UUID id=UUID.fromString(args.get("structure_id").getAsString());
        String operation=args.has("operation")?args.get("operation").getAsString():"analyze";
        if(!Set.of("analyze","simulate","recommend","apply").contains(operation)) throw new IllegalArgumentException("未知配平操作");
        double rpm=number(args,"reference_rpm",64,-256,256);
        String fill=args.has("balloon_fill")?args.get("balloon_fill").getAsString():"target";
        if(!Set.of("target","current").contains(fill)) throw new IllegalArgumentException("balloon_fill 应为 target 或 current");
        double maximum=number(args,"max_ballast_blocks",8,0,64);
        if(maximum!=Math.rint(maximum)) throw new IllegalArgumentException("配重块数必须为整数");
        var limits=new PhysicsSimulation.Limits(number(args,"duration_seconds",6,1,30),number(args,"max_tilt_degrees",8,.1,89),
                number(args,"max_vertical_acceleration",.25,.001,100),number(args,"max_angular_acceleration",.035,.00001,10),
                number(args,"perturbation_degrees",2,.01,20));
        Map<String,Double> controls=new LinkedHashMap<>();
        if(args.has("controls")) for(var entry:args.getAsJsonObject("controls").entrySet()) {
            double value=entry.getValue().getAsDouble();
            if(!Double.isFinite(value)||Math.abs(value)>4) throw new IllegalArgumentException("推力倍率应在 -4 至 4 之间");
            controls.put(entry.getKey(),value);
        }
        if(args.has("edits")) {
            if(!args.get("edits").isJsonArray()||args.getAsJsonArray("edits").size()>64) throw new IllegalArgumentException("单次最多试算或施工 64 格补丁");
            Set<String> cells=new java.util.HashSet<>();
            for(var raw:args.getAsJsonArray("edits")) {
                var edit=raw.getAsJsonObject();
                if(!edit.has("block_id")||!edit.get("block_id").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    throw new IllegalArgumentException("补丁需要明确方块编号；拆除用 minecraft:air");
                var p=edit.getAsJsonObject("position");
                for(String axis:ListAxes.NAMES) { double coordinate=number(p,axis,Double.NaN,-256,256); if(coordinate!=Math.rint(coordinate)) throw new IllegalArgumentException("补丁坐标必须为整数"); }
                if(!cells.add(p.get("x")+","+p.get("y")+","+p.get("z"))) throw new IllegalArgumentException("同一格不能重复声明");
            }
        }
        if(operation.equals("apply") && (!args.has("edits")||args.getAsJsonArray("edits").isEmpty()))
            throw new IllegalArgumentException("施工必须提供选定的 edits；推荐不会自动替换设计");
        return new PhysicsBalanceParameters(id,operation,rpm,fill,(int)maximum,limits,controls,args);
    }
    private static double number(JsonObject args,String key,double fallback,double min,double max) {
        double value=args.has(key)?args.get(key).getAsDouble():fallback;
        if(!Double.isFinite(value)||value<min||value>max) throw new IllegalArgumentException("参数范围无效: "+key);
        return value;
    }
    private static final class ListAxes { static final String[] NAMES={"x","y","z"}; }
}
