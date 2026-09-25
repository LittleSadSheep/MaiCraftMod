// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Map;

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
        System.out.println("MachineBuildEvidenceTest: passed");
    }
}
