package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.task.TaskResult;

/** 复现固定翼改造：前一格已经原生放置及调向，下一格被轮座遮住，失败账本必须保留实际施工。 */
public final class PartialNativeEffectsTest {
    public static void main(String[] args) {
        var nativeEffects = List.of(Map.of("action", "place", "native_confirmed", true),
                Map.of("action", "rotate", "native_confirmed", true));
        var nativeRemaining = List.of(Map.of("target_index", 1, "block_id", "create:gearbox"));
        var diff = List.of(Map.of("target_index", 0, "matches", true), Map.of("target_index", 1, "matches", false));
        var failed = new TaskResult(false, "下一格施工面被轮座遮挡", false, false,
                Map.of("completed_effects", nativeEffects, "remaining_effects", nativeRemaining, "declared_structure_diff", diff));
        var current = List.<Map<String, Object>>of(Map.of("step_index", 0, "state", "failed_current"));
        var first = IntentTask.withEffectLedger(failed, List.of(), current, List.of());
        check(first.data().get("completed_effects").equals(nativeEffects), "first-step failure lost placed gearbox");
        check(first.data().get("remaining_effects").equals(nativeRemaining), "native unfinished cells were replaced");
        check(first.data().get("declared_structure_diff").equals(diff), "whole-machine comparison was lost");
        check(first.data().get("remaining_step_effects").equals(current), "failed semantic step is still visible");

        // 前面的整项任务已成功时，同时保留那些步骤和当前未完成步骤里的部分原生效果。
        var prior = List.<Map<String, Object>>of(Map.of("step_index", 0, "success", true));
        var later = IntentTask.withEffectLedger(failed, prior, current, List.of());
        check(later.data().get("completed_effects").equals(nativeEffects)
                && later.data().get("completed_step_effects").equals(prior), "earlier step overwrote current native effects");
        var ordinary = IntentTask.withEffectLedger(new TaskResult(false, "未开始施工", false, false, Map.of()), prior, current, List.of());
        check(ordinary.data().get("completed_effects").equals(prior)
                && ordinary.data().get("remaining_effects").equals(current), "ordinary sequence ledger changed");
        check(!first.success() && !later.success(), "partial native effects cannot turn failure into success");
        System.out.println("PartialNativeEffectsTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
