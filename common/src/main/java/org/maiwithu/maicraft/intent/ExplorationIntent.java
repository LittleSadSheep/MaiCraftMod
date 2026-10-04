package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.explore.ExplorationSector;

/** LLM 选择想找的群系或结构；没有指定种类时，角色按给定扇区跑图并积累现场观察。 */
public final class ExplorationIntent {
    public static final String ABILITY = "maicraft:explore";
    private ExplorationIntent() {}

    public static IntentAction adapt(Goal goal) {
        // 先确定跑图、群系或世界结构三种目的，再把范围和方向交给对应执行器；不替模型评价地点是否适合用途。
        if (goal.target() != null) throw new IllegalArgumentException("explore uses discovery parameters, not a located target");
        JsonObject p = goal.parameters();
        long kinds = List.of("biome_id", "biome_tag", "structure_id", "semantic_target").stream().filter(p::has).count();
        if (kinds > 1) throw new IllegalArgumentException("choose one biome_id, biome_tag, structure_id or semantic_target");
        if (p.has("structure_id")) {
            // 世界结构需要自己的线索与投眼确认，沿用结构搜索入口，不能把结构编号当成群系筛选。
            return AbilityAdapter.adapt(new Goal("maicraft:find_structure", goal.outcome(), null,
                    p.toString(), goal.preferencesJson(), goal.constraints(), goal.children()), null, null);
        }
        if (p.has("reach_structure") || p.has("allow_rare_consumables"))
            throw new IllegalArgumentException("reach_structure and allow_rare_consumables require structure_id");
        var mode = TransportMode.parse(p.has("transport_mode") ? p.get("transport_mode").getAsString() : null);
        if (mode != TransportMode.AUTO && mode != TransportMode.GROUND)
            throw new IllegalArgumentException("unlocated exploration uses auto or ground transport");
        JsonObject args = new JsonObject();
        String target = p.has("biome_id") ? p.get("biome_id").getAsString()
                : p.has("biome_tag") ? "#" + p.get("biome_tag").getAsString().replaceFirst("^#", "")
                : p.has("semantic_target") ? p.get("semantic_target").getAsString() : "survey";
        args.addProperty("target", target);
        args.addProperty("transport_mode", mode.name().toLowerCase(Locale.ROOT));
        int radius = integer(p, "max_distance", 768);
        if (radius < 64 || radius > 2048) throw new IllegalArgumentException("biome/survey max_distance must be 64..2048");
        args.addProperty("max_distance", radius);
        if (p.has("may_alter_terrain")) args.add("may_alter_terrain", p.get("may_alter_terrain"));
        copySector(p, args, radius);
        return new IntentAction.Tool("explore", args.toString());
    }

    /** 旅行与找结构共用同一份方向契约，避免外层接收了方向却在内部任务中丢失。 */
    public static void copySector(JsonObject from, JsonObject to, int radius) {
        String direction = from.has("direction") ? from.get("direction").getAsString() : null;
        Integer angle = from.has("angle_degrees") ? integer(from, "angle_degrees", 90) : null;
        Integer minimum = from.has("min_distance") ? integer(from, "min_distance", 0) : null;
        ExplorationSector.of(direction, angle, minimum, radius);
        for (String field : List.of("direction", "angle_degrees", "min_distance"))
            if (from.has(field)) to.add(field, from.get(field).deepCopy());
    }

    private static int integer(JsonObject values, String name, int fallback) {
        if (!values.has(name)) return fallback;
        try { return values.get(name).getAsBigDecimal().intValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(name + " must be an integer"); }
    }
}
