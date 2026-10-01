// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprint;

/** 留存已经编译的最终逐格目标；整机比图不要求局部修改后的旧原生结构还能重新施工。 */
public final class MachineComparisonTargets {
    private static final String KEY = "maicraft_comparison_targets";
    private MachineComparisonTargets() {}

    public static JsonArray read(MachineBlueprint blueprint) {
        var document = blueprint.blueprint();
        if (document.has("metadata") && document.getAsJsonObject("metadata").has(KEY))
            return document.getAsJsonObject("metadata").getAsJsonArray(KEY).deepCopy();
        // 旧的完整档案首次读取时按旧编译器迁移；新改造以后只覆盖本次声明的最终格。
        return capture(ClientMachineCatalog.blueprintPlan(blueprint));
    }

    public static void record(JsonObject document, MachineBlueprint previous, MachineConstructionPlan patch) {
        var rows = new LinkedHashMap<String, JsonObject>();
        if (previous != null) for (var raw : read(previous)) {
            var row = raw.getAsJsonObject(); rows.put(key(row), row.deepCopy());
        }
        for (var raw : capture(patch)) {
            var row = raw.getAsJsonObject(); String at = row.get("offset").toString();
            // 新带段声明了其全部格子，覆盖旧带轮、旧带中段或旧部件；范围外的旧目标继续参与整机 diff。
            rows.entrySet().removeIf(entry -> entry.getValue().get("offset").toString().equals(at)
                    && (!row.has("part") || !entry.getValue().has("part")));
            rows.put(key(row), row);
        }
        var targets = new JsonArray(); rows.values().forEach(targets::add);
        var metadata = document.has("metadata") ? document.getAsJsonObject("metadata") : new JsonObject();
        metadata.add(KEY, targets); document.add("metadata", metadata);
    }

    private static JsonArray capture(MachineConstructionPlan plan) {
        var rows = new LinkedHashMap<String, JsonObject>();
        for (var target : plan.blocks()) {
            var row = MachineBlueprintDiff.state(target.desiredState());
            row.getAsJsonObject("properties").keySet().removeIf(name -> !target.exactProperties().contains(name));
            row.add("offset", MachineAssemblyDocument.json(target.pos().subtract(plan.anchor()))); rows.put(key(row), row);
        }
        for (var installation : plan.installations()) installation.targets().forEach((at, state) -> {
            var row = MachineBlueprintDiff.state(state); row.add("offset", MachineAssemblyDocument.json(at.subtract(plan.anchor())));
            rows.put(key(row), row);
        });
        for (var part : plan.parts()) {
            var row = new JsonObject(); row.add("offset", MachineAssemblyDocument.json(part.position().subtract(plan.anchor())));
            row.addProperty("item_id", part.spec().itemId());
            row.addProperty("part", part.spec().side() == null ? "center" : part.spec().side().getSerializedName()); rows.put(key(row), row);
        }
        var result = new JsonArray(); rows.values().forEach(result::add); return result;
    }
    private static String key(JsonObject row) { return row.get("offset") + "/" + (row.has("part") ? row.get("part").getAsString() : "block"); }
}
