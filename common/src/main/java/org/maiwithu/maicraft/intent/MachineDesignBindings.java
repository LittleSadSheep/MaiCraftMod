// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** 纯结构审阅无需场地；可选现场审阅成对携带观察编号和目标，正常施工直接使用场地感知与 plan。 */
public final class MachineDesignBindings {
    public static final List<String> TARGET_KINDS = List.of("landmark", "area");
    private MachineDesignBindings() {}
    public static void validate(Goal goal) {
        if (!goal.ability().equals(MachineAbilityAdapter.DESIGN)) return;
        JsonObject parameters = goal.parameters(); boolean snapshot = parameters.has("snapshot_id");
        if ((goal.target() != null) != snapshot) throw new IllegalArgumentException("machine_design_site_binding: omit both target and snapshot_id for a generic review, or copy both from the same current observation; ordinary construction uses construction_site then plan build_machine directly");
        if (!snapshot) return;
        var value = parameters.get("snapshot_id");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank() || value.getAsString().length() > 36)
            throw new IllegalArgumentException("snapshot_id must be a nonempty string up to 36 characters");
        var target = goal.target();
        if (!TARGET_KINDS.contains(target.kind()) || target.label() == null || target.label().isBlank() || target.position() != null || target.relation() != null)
            throw new IllegalArgumentException("machine_design_site_binding: use the inspected landmark/area label, without coordinates or prior-result relation");
    }
    public static void describe(JsonObject goal) {
        // Schema 与入口共用同一目标集合；字段成对出现，避免模型补了坐标又漏掉勘察回执。
        JsonObject rule = JsonParser.parseString("""
                {"if":{"properties":{"ability":{"const":"maicraft:design_machine"}},"required":["ability"]},
                 "then":{"oneOf":[
                   {"properties":{"target":{"type":"null"},"parameters":{"not":{"required":["snapshot_id"]}}}},
                   {"required":["target","parameters"],"properties":{
                     "target":{"type":"object","properties":{"kind":{},"relation":{"type":"null"}}},
                     "parameters":{"required":["snapshot_id"],"properties":{"snapshot_id":{"type":"string","minLength":1,"maxLength":36}}}}}]}}
                """).getAsJsonObject();
        JsonArray kinds = new JsonArray(); TARGET_KINDS.forEach(kinds::add);
        rule.getAsJsonObject("then").getAsJsonArray("oneOf").get(1).getAsJsonObject().getAsJsonObject("properties")
                .getAsJsonObject("target").getAsJsonObject("properties").getAsJsonObject("kind").add("enum", kinds);
        JsonArray constraints = new JsonArray(); constraints.add(rule); goal.add("allOf", constraints);
    }
}
