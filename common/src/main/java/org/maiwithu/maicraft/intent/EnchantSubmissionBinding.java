// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.maiwithu.maicraft.core.task.enchant.EnchantTaskRecord;
import org.maiwithu.maicraft.core.task.enchant.EnchantmentSubmissionJournal;

/** 用持久总任务身份绑定一次附魔；重启或插入其他准备步骤不会让同一消费获得一个全新编号。 */
final class EnchantSubmissionBinding {
    private EnchantSubmissionBinding() {}

    static void bind(EnchantTaskRecord child, IntentTaskRecord parent, IntentRuntime runtime) {
        var journal = new EnchantmentSubmissionJournal(runtime.requiredStateIdentity(), operationId(parent));
        child.submissionBarrier(barrier(parent, runtime, journal::prepare));
    }

    static BooleanSupplier barrier(IntentTaskRecord parent, IntentRuntime runtime, BooleanSupplier reserve) {
        UUID operation = operationId(parent);
        return new BooleanSupplier() {
            private CompletableFuture<Void> checkpoint;
            private boolean durable;
            private IllegalStateException failed;

            @Override public boolean getAsBoolean() {
                if (failed != null) throw failed;
                try {
                    runtime.requireCurrentBinding(parent);
                    if (!operation.equals(operationId(parent))) throw new IllegalStateException("enchantment_parent_step_changed");
                    if (!durable) {
                        // 父任务编号、请求去重键和当前步骤必须先真正落盘；内存里已有快照不能保护进程崩溃后的重发。
                        if (checkpoint == null) checkpoint = runtime.checkpointBeforeEnchantment(parent);
                        else if (checkpoint.isCancelled()) checkpoint = runtime.followEnchantmentCheckpoint(parent);
                        if (!checkpoint.isDone()) return false;
                        checkpoint.join(); durable = true;
                    }
                } catch (CancellationException superseded) {
                    // 普通检查点可能合并掉正在等待的旧版本；下一刻跟随替代版本，不把取消当成成功或跳过后来的写入失败。
                    return false;
                } catch (RuntimeException failure) {
                    failed = new IllegalStateException("enchantment_parent_checkpoint_failed: do not submit the enchantment button", failure);
                    throw failed;
                }
                // 检查点完成后才预留一次附魔；预留仍须自行同步成功，任何等待阶段都不发出原生按钮。
                return reserve.getAsBoolean();
            }
        };
    }

    static UUID operationId(IntentTaskRecord parent) {
        JsonObject goal = parent.steps().get(parent.stepIndex()).toJson(); int previousMatches = 0;
        // 两个明确排列的相同附魔目标可以分别执行；恢复前插入不同的取材/观察步骤不会改变原附魔的标识。
        for (int index = 0; index < parent.stepIndex(); index++)
            if (parent.steps().get(index).toJson().equals(goal)) previousMatches++;
        String identity = "enchant:" + parent.externalId() + ":" + previousMatches + ":" + canonical(goal);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonElement canonical(JsonElement value) {
        // 对象键的书写顺序不代表新请求；数组中的真实步骤顺序则保持原样。
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> out.add(key, canonical(value.getAsJsonObject().get(key))));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray(); value.getAsJsonArray().forEach(child -> out.add(canonical(child))); return out;
        }
        return value.deepCopy();
    }
}
