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
import org.maiwithu.maicraft.core.task.base.NativeConsumptionTaskRecord;
import org.maiwithu.maicraft.core.task.base.NativeConsumptionJournal;

/** 用持久总任务身份绑定原生消费；保留旧附魔标识，重启或插入准备步骤不会重新放行同一次投料。 */
final class EnchantSubmissionBinding {
    private EnchantSubmissionBinding() {}

    static void bind(NativeConsumptionTaskRecord child, IntentTaskRecord parent, IntentRuntime runtime) {
        String namespace = child.consumptionNamespace();
        var journal = new NativeConsumptionJournal(runtime.requiredStateIdentity(), operationId(parent, namespace), namespace);
        child.submissionBarrier(barrier(parent, runtime, namespace, journal::prepare));
    }

    static BooleanSupplier barrier(IntentTaskRecord parent, IntentRuntime runtime, BooleanSupplier reserve) {
        return barrier(parent, runtime, "enchant", reserve);
    }

    static BooleanSupplier barrier(IntentTaskRecord parent, IntentRuntime runtime, String namespace, BooleanSupplier reserve) {
        UUID operation = operationId(parent, namespace);
        return new BooleanSupplier() {
            private CompletableFuture<Void> checkpoint;
            private boolean durable;
            private IllegalStateException failed;

            @Override public boolean getAsBoolean() {
                if (failed != null) throw failed;
                try {
                    runtime.requireCurrentBinding(parent);
                    if (!operation.equals(operationId(parent, namespace))) throw new IllegalStateException("native_consumption_parent_step_changed");
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
                    String prefix = namespace.equals("enchant") ? "enchantment_parent_checkpoint_failed" : "native_consumption_parent_checkpoint_failed";
                    failed = new IllegalStateException(prefix + ": do not submit the native operation", failure);
                    throw failed;
                }
                // 检查点完成后才预留一次附魔；预留仍须自行同步成功，任何等待阶段都不发出原生按钮。
                return reserve.getAsBoolean();
            }
        };
    }

    static UUID operationId(IntentTaskRecord parent) {
        return operationId(parent, "enchant");
    }

    static UUID operationId(IntentTaskRecord parent, String namespace) {
        JsonObject goal = identityGoal(parent.steps().get(parent.stepIndex())); int previousMatches = 0;
        // 两个明确排列的相同附魔目标可以分别执行；恢复前插入不同的取材/观察步骤不会改变原附魔的标识。
        for (int index = 0; index < parent.stepIndex(); index++)
            if (identityGoal(parent.steps().get(index)).equals(goal)) previousMatches++;
        String identity = namespace + ":" + parent.externalId() + ":" + previousMatches + ":" + canonical(goal);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject identityGoal(Goal source) {
        JsonObject goal = source.toJson(); JsonObject parameters = goal.getAsJsonObject("parameters");
        // v2重启后只更新场地观察不代表再次消费；仅忽略这一个临时引用，配方、预算、地点和旧附魔目标均原样计入身份。
        if ((MachineAbilityAdapter.OPERATE.equals(source.ability()) || MachineAbilityAdapter.BUILD.equals(source.ability()))
                && parameters.has("production") && parameters.get("production").isJsonObject()
                && MachineProductionIntent.isNative(parameters.getAsJsonObject("production"))) parameters.remove("snapshot_id");
        return goal;
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
