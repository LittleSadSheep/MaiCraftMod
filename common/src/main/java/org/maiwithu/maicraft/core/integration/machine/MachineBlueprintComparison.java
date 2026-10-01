// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.Level;

/** 建成后只做一轮全蓝图观察；按刻分段读取，大机器不会在一个游戏刻里阻塞主线程。 */
public final class MachineBlueprintComparison {
    private final MachineBlueprintDiff diff;
    private final String dimension;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final JsonArray differences = new JsonArray();
    private int cursor;
    private long started = -1, observed = -1;
    private JsonObject operatingState;
    public MachineBlueprintComparison(MachineConstructionPlan plan, String dimension) {
        this(new MachineBlueprintDiff(plan), dimension);
    }
    /** 已归档整机只读取冻结目标，不要求它在修改后仍能作为一次新的完整施工计划编译。 */
    public MachineBlueprintComparison(MachineBlueprintDiff targets, String dimension) {
        diff = targets; this.dimension = dimension;
        MachineBlueprintDiff.COUNTERS.forEach(key -> counts.put(key,0));
    }
    public boolean advance(Level world, int budget) {
        if (complete()) return true;
        if (started < 0) started = world.getGameTime(); observed = world.getGameTime();
        var page = diff.page(world,dimension,cursor,budget);
        MachineBlueprintDiff.COUNTERS.forEach(key -> counts.merge(key,page.get(key).getAsInt(),Integer::sum));
        for (var row : page.getAsJsonArray("differences")) {
            // 只保留真实差异，但完整保留这些决策事实，不能在第128个差异后让模型另开一轮翻页。
            differences.add(row.deepCopy());
        }
        cursor += page.get("examined").getAsInt();
        if (complete()) operatingState = diff.operatingState(world, dimension);
        return complete();
    }
    public boolean complete() { return cursor >= diff.size(); }
    public JsonObject report() {
        var result = new JsonObject(); result.addProperty("scope","declared_block_states_and_native_parts");
        result.addProperty("extra_block_scope","explicit_air_targets_only"); result.addProperty("observation_only",true);
        result.addProperty("total_targets",diff.size()); result.addProperty("examined",cursor); counts.forEach(result::addProperty);
        result.addProperty("scan_complete",complete()); result.addProperty("observed_from_tick",started); result.addProperty("observed_through_tick",observed);
        boolean known = complete() && counts.get("unknown") == 0;
        result.addProperty("comparison_complete",known);
        result.add("structure_matches_blueprint",known ? new JsonPrimitive(counts.get("matched") == diff.size()) : JsonNull.INSTANCE);
        result.add("differences",differences.deepCopy()); result.addProperty("details_truncated",false);
        if (operatingState != null) result.add("operating_state", operatingState.deepCopy());
        result.addProperty("production_verified",false); return result;
    }
}
