// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** 纯结构审阅无需场地；指向实际场地时必须同时绑定最新勘察编号和同名目标。 */
public final class MachineDesignBindings {
    public static final List<String> TARGET_KINDS = List.of("landmark", "area");
    private MachineDesignBindings() {}
    public static void validate(Goal goal) {
        if (!goal.ability().equals(MachineAbilityAdapter.DESIGN)) return;
        JsonObject parameters = goal.parameters(); boolean snapshot = parameters.has("snapshot_id");
        if ((goal.target() != null) != snapshot) throw new IllegalArgumentException("machine_design_site_binding: omit both target and snapshot_id for a generic review; otherwise inspect_machine first and supply both");
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
