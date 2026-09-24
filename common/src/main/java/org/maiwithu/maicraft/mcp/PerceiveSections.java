package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * perceive 响应的逐段投影：调用方声明只要哪些段，响应就只保留这些段。
 *
 * <p>执行顺序：调用方传 {@code sections} 点名 → 校验段名属于该视图 → 运行时装完完整响应
 * → {@link #select} 按声明裁剪 → 返回只含点名段的响应。
 *
 * <p>存在的理由：完整快照是给"什么都不知道"的调用方用的，单次响应可以到上万字符。
 * 任何下游都必然有上下文预算，于是完整的快照会被按字节截断，而截断点落在哪一段
 * 取决于字段顺序——调用方要的段可能正好被切掉，还会把"没读到"误当成"没有"。
 * 让调用方点名所需段，是唯一能保证"要的段一定读得到"的做法。
 *
 * <p>段名就是响应对象的顶层键：调用方从默认的完整响应里直接看得到可用段名，
 * 不需要另建一份清单。校验拒收未知段名（fail-closed），避免拼错后静默返回空。
 */
final class PerceiveSections {

    /** 逐段查看方式；其它 view 的响应不是"段袋"，不参与投影。 */
    static final String SECTIONS = "sections";

    /** 请求了、但本次确实没有产出的段，在这里点名——缺失必须能和执行方区分开。 */
    static final String UNAVAILABLE = "sections_unavailable";

    // situation 的可选段：基础身体状态 + 背包装备 +（按 focus 追加的）诊断段。
    private static final Set<String> SITUATION = Set.of(
            "dimension", "position", "view", "health", "max_health", "food", "air",
            "in_water", "underwater", "swimming", "sprinting", "day", "is_daytime",
            "time_phase", "time_of_day", "day_index", "weather", "game_time",
            "inventory", "equipment", "vehicle_type", "task",
            "elevators", "actor", "tick_stage", "controlling_task", "navigation",
            "collision_geometry", "transport", "landing_assist", "jetpack", "physical_structures");

    // surroundings 的可选段：位置与光照、邻近实体与告示牌、脚下可走面、地形缩略、电梯、视线与物理结构。
    private static final Set<String> SURROUNDINGS = Set.of(
            "position", "dimension", "sky_light", "biome", "nearby_entities", "nearby_signs",
            "sign_observation", "local_decision_summary", "terrain_overview", "elevators",
            "view", "physical_structures");

    private PerceiveSections() {
    }

    /** 只有"段袋"形状的响应能裁剪；其它 view 保持原样，避免被误当成可裁剪对象。 */
    static boolean supports(String view) {
        return "situation".equals(view) || "surroundings".equals(view);
    }

    /** 该视图可能产出的段名全集；校验用它拒收拼错或跨视图的段名。 */
    static Set<String> known(String view) {
        if ("situation".equals(view)) return SITUATION;
        if ("surroundings".equals(view)) return SURROUNDINGS;
        return Set.of();
    }

    /** 模型选观察段之前，从校验所用的同一清单生成说明，避免把周围环境当成身体状态查询。 */
    static JsonObject schema() {
        var names = new TreeSet<>(SITUATION);
        names.addAll(SURROUNDINGS);
        var choices = new JsonArray();
        names.forEach(choices::add);
        var items = new JsonObject();
        items.addProperty("type", "string");
        items.add("enum", choices);
        var result = new JsonObject();
        result.addProperty("type", "array");
        result.addProperty("minItems", 1);
        result.addProperty("maxItems", 32);
        result.add("items", items);
        result.addProperty("description", "Select top-level sections for one view only. "
                + "situation: " + sectionNames("situation") + ". "
                + "surroundings: " + sectionNames("surroundings") + ". "
                + "Use separate requests for sections belonging to different views. "
                + "Focus diagnostics still require the corresponding focus. "
                + "Omitting sections returns the full snapshot. Omitting terrain_overview from a surroundings "
                + "selection skips the terrain sampling wait. Requested but unproduced sections are listed in "
                + UNAVAILABLE + "; absence means unknown, not empty.");
        return result;
    }

    /** 拒绝错误段名时同时返回合法选择，让角色下一轮能改正请求而不必重读完整快照。 */
    static String invalidSection(String view, String name) {
        String other = "situation".equals(view) ? "surroundings" : "situation";
        String hint = known(other).contains(name) ? "; request view=" + other + " for " + name : "";
        return "unknown " + view + " section: " + name + hint + "; valid sections: " + sectionNames(view);
    }

    private static String sectionNames(String view) {
        // 固定顺序也固定工具说明，避免集合迭代顺序改变模型的缓存前缀。
        return String.join(", ", new TreeSet<>(known(view)));
    }

    /**
     * 读出这次的逐段声明并去重保序。
     *
     * <p>未声明（字段缺失或为 null）返回 null，语义是"要完整快照"——保持既有调用方行为不变；
     * 去重保序让同一段名重复出现时只算一段，返回顺序跟随调用方书写顺序，便于对照排查。
     */
    static List<String> requested(JsonObject arguments) {
        JsonElement element = arguments.get(SECTIONS);
        if (element == null || element.isJsonNull()) return null;
        JsonArray names = element.getAsJsonArray();
        var unique = new LinkedHashSet<String>();
        for (JsonElement item : names) unique.add(item.getAsString());
        return List.copyOf(unique);
    }

    /**
     * 这一次请求是否要某一段：未声明逐段选择时视为全都要。
     *
     * <p>调用方用它跳过只为某段准备的昂贵工作。已确认的口径：没点名 terrain_overview 就不需要
     * 地形数据——地形缩略图是分帧采样、可能要等几十个客户端帧，而"只要电梯楼层"这类窄查询
     * 与地形无关，等它纯属白付代价。未声明 sections 时仍然照旧等待，行为对既有调用方不变。
     */
    static boolean wants(List<String> requested, String section) {
        return requested == null || requested.contains(section);
    }

    /**
     * 按声明裁剪响应：只保留点名的段，并点名本次没产出的段。
     *
     * <p>取值是直接引用而非深拷贝——payload 由本次调用的运行时刚构造出来，没有第二个持有者，
     * 复制一份只会白费内存。
     *
     * <p>点名的段这一轮确实没有产出（例如 situation 的 focus 没开对应诊断）时记入
     * {@link #UNAVAILABLE}：缺段必须显式说明，否则调用方无法区分"这个段是空的"与
     * "这个段这次没读到"，正是本次要修掉的那类误判。
     */
    static JsonObject select(JsonObject payload, List<String> requested) {
        if (requested == null) return payload;
        var selected = new JsonObject();
        var unavailable = new JsonArray();
        for (String section : requested) {
            JsonElement value = payload.get(section);
            if (value == null || value.isJsonNull()) unavailable.add(section);
            else selected.add(section, value);
        }
        if (!unavailable.isEmpty()) selected.add(UNAVAILABLE, unavailable);
        return selected;
    }
}
