// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 复现机械手下方最后一根轴尚未放置的失败回执，首层必须保留已完成十九格的事实。 */
public final class MachineBuildEvidenceTest {
    public static void main(String[] args) {
        var evidence = Map.<String, Object>of("requested", 20, "completed", 19, "placed", 11,
                "failure_code", "placement_stances_exhausted", "failure_position", Map.of("x", -150, "y", 95, "z", -32),
                "placement_access", Map.of("reason", "edge_support_or_sweep_changed", "reachable_stances", 52),
                "build_diagnostics", List.of(Map.of("expected", "create:shaft[axis=x]", "observed", "minecraft:air")));
        var direct = MachineBuildEvidence.summarize("blocks", evidence);
        var supplied = MachineBuildEvidence.summarize("blocks", Map.of("last_build_evidence", evidence));
        if (!direct.equals(supplied) || !direct.get("verified_blocks").equals(19)
                || !direct.get("expected").equals("create:shaft[axis=x]") || !direct.get("reachable_stances").equals(52))
            throw new AssertionError("机器回执必须保留底层已完成目标与具体缺失位置");
        // 未取得原生结果时不伪造零进度，附加部件计数也不能混入方块完成数。
        if (MachineBuildEvidence.summarize("parts", Map.of("installed_parts", 0)).containsKey("verified_blocks"))
            throw new AssertionError("缺少方块证据不等于零格完成");
        // 机械手缺磨制玫瑰石英时，保留加工交接与恢复选项，不能只有一层泛化的缺料错误。
        var handoff = Map.of("knowledge_uris", List.of("maicraft://knowledge/recipes/create/polished_rose_quartz"));
        var supply = Map.of("planning_handoff", handoff, "recovery_options", List.of(Map.of("id", "plan_material_process")),
                "body_preparation_required", true, "food_preparation", Map.of("food", 10));
        var visible = new LinkedHashMap<String, Object>(); MachineBuildEvidence.retainSupplyFailure(visible, supply);
        if (!handoff.equals(visible.get("planning_handoff")) || !Boolean.TRUE.equals(visible.get("material_planning_required"))
                || !supply.equals(visible.get("material_supply_failure"))) throw new AssertionError("机器丢失了原料加工交接");
        if (!Boolean.TRUE.equals(visible.get("body_preparation_required"))) throw new AssertionError("机器丢失了身体前置");
        System.out.println("MachineBuildEvidenceTest: passed");
    }
}
