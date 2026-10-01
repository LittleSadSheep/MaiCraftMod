// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.task.TaskResult;

/** 原生加工把装料与返料交给搬运子任务，父回执保留每次实际数量及尚未结清的效果。 */
public final class MenuTransferEvidence {
    private final List<Map<String, Object>> results = new ArrayList<>();
    private boolean uncertain, retryBlocked, effectsStarted;

    public void retain(String phase, TaskResult result) {
        // 先保留子任务原始结算，再释放执行器；不能只剩“附魔搬运失败”而丢失已经入槽的材料。
        results.add(Map.of("phase", phase, "success", result.success(), "message", result.message(),
                "timed_out", result.timedOut(), "interrupted", result.interrupted(), "data", result.data()));
        uncertain |= Boolean.TRUE.equals(result.data().get("outcome_uncertain"));
        retryBlocked |= Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed"));
        effectsStarted |= Boolean.TRUE.equals(result.data().get("effects_started"));
    }

    public void appendTo(Map<String, Object> data) {
        // 搬运未确认时即使还没按加工按钮也存在未决物品，父任务不得覆盖成“未消费，可直接重试”。
        if (!results.isEmpty()) data.put("transfer_results", List.copyOf(results));
        if (effectsStarted) data.put("effects_started", true);
        if (uncertain) data.put("outcome_uncertain", true);
        if (uncertain || retryBlocked) data.put("mechanical_retry_allowed", false);
    }

    public static boolean canRefreshBeforeSubmission(TaskResult result) {
        // 已证明零点击、无效果且无未知项时，父任务可按当前槽位重建这一笔；缺少证明时一律保留原失败。
        return !result.success() && !result.interrupted()
                && !Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed"))
                && Boolean.FALSE.equals(result.data().get("effects_started"))
                && Boolean.FALSE.equals(result.data().get("outcome_uncertain"))
                && result.data().get("submitted_clicks") instanceof Number clicks && clicks.intValue() == 0;
    }
}
