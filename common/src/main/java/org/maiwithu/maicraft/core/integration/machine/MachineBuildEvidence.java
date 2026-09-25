// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把当前施工批次的真实完成数和失败格提到机器回执首层，避免用附加部件计数解释方块施工。 */
public final class MachineBuildEvidence {
    private MachineBuildEvidence() {}

    public static Map<String, Object> summarize(String phase, Map<String, Object> nativeStage) {
        // 生存供料包裹一层原生施工证据；创造施工直接返回同一份方块结果，两种入口采用相同口径。
        Map<?, ?> evidence = nativeStage.get("last_build_evidence") instanceof Map<?, ?> nested ? nested : nativeStage;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase);
        result.put("scope", "current block preparation or attachment batch; not whole-machine completion");
        copy(evidence, result, "requested", "requested_blocks");
        copy(evidence, result, "completed", "verified_blocks");
        copy(evidence, result, "placed", "placement_counter");
        copy(evidence, result, "cleared", "cleared_blocks");
        copy(evidence, result, "temporary_supports_remaining", "temporary_supports_remaining");
        for (String key : List.of("failure_code", "failure_type", "failure_position", "stopped_phase", "temporary_support_demand"))
            copy(evidence, result, key, key);
        if (evidence.get("placement_access") instanceof Map<?, ?> access) {
            // 首层保留决定恢复方向的站位结论，完整身体轨迹继续从原回执查询。
            for (String key : List.of("reason", "posture_reason", "checked_stances", "reachable_stances"))
                copy(access, result, key, key);
        }
        if (evidence.get("build_diagnostics") instanceof List<?> diagnostics && !diagnostics.isEmpty()
                && diagnostics.getFirst() instanceof Map<?, ?> first) {
            for (String key : List.of("expected", "observed", "target_index")) copy(first, result, key, key);
        }
        return Map.copyOf(result);
    }

    private static void copy(Map<?, ?> source, Map<String, Object> target, String from, String to) {
        // 缺失字段继续表示未取得证据，不能补零而把未知施工进度写成没有放置。
        Object value = source.get(from);
        if (value != null) target.put(to, value);
    }
}
