// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.core.task.chat.ChatTask;
import org.maiwithu.maicraft.core.task.chat.ChatTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

// 连续提交新任务会立即取消还在输入中的旧聊天任务，而取消发生在命令发出之前。
// 回执必须区分这一形态：调用方不能把“没发出去”当成“发完才被叫停”。
public final class ChatCancelObservabilityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // 动作层：打字中途被接管取消（等同另一任务占坑），命令从未尝试提交。
        ChatTask task = (ChatTask) TaskFactory.create(null,
                new ChatTaskRecord("chat-cancel-observability", new ChatMessage("/time set day", 100)));
        task.stop(null, Task.StopReason.REPLACED);
        TaskResult cancelled = task.result(TaskState.CANCELLED);
        check(!cancelled.success() && cancelled.interrupted(), "before-submit cancellation stays an interrupted failure");
        check("cancelled_before_submit".equals(cancelled.cancelSource()),
                "cancel source names the before-submit shape like body_gone/operator_cancel");
        check(cancelled.message().contains("NOT submitted"), "receipt message states the command was not submitted");
        check("not_submitted".equals(cancelled.data().get("delivery_status")),
                "evidence keeps delivery as not submitted");

        // 语义层：父任务的通用取消回执必须把子任务的未发出事实带到顶层 cancel_source。
        TaskResult parent = IntentTask.withInterruptedEffects(
                TaskResult.cancelled("semantic task cancelled", "takeover"), cancelled);
        JsonObject json = JsonParser.parseString(parent.toJson()).getAsJsonObject();
        check("cancelled_before_submit".equals(json.get("cancel_source").getAsString()),
                "semantic receipt surfaces the before-submit cancel source at top level");
        check(json.getAsJsonObject("data").getAsJsonObject("interrupted_child") != null,
                "interrupted child evidence stays attached for reconciliation");

        // 没有更具体来源的子任务不改动父回执的取消来源。
        TaskResult untouched = IntentTask.withInterruptedEffects(
                TaskResult.cancelled("semantic task cancelled", "operator_cancel"),
                TaskResult.fail("partial deposit"));
        JsonObject untouchedJson = JsonParser.parseString(untouched.toJson()).getAsJsonObject();
        check("operator_cancel".equals(untouchedJson.get("cancel_source").getAsString()),
                "generic child results leave the parent cancel source alone");

        System.out.println("ChatCancelObservabilityTest: passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
