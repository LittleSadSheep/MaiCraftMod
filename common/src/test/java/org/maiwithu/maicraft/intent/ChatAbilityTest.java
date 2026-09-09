// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.task.chat.ChatTask;
import org.maiwithu.maicraft.core.task.chat.ChatTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskState;
import java.util.List;

public final class ChatAbilityTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        check(IntentRuntime.KNOWN_ABILITIES.contains("maicraft:chat"), "chat is discoverable");
        check(SemanticAbilityCatalog.parameterNames("maicraft:chat").equals(
                java.util.Set.of("text", "typing_interval_ms")), "bounded semantic contract");
        for (String text : List.of("你好 👋", "/home")) {
            JsonObject parameters = new JsonObject(); parameters.addProperty("text", text);
            Goal goal = goal(parameters);
            IntentRuntime.get().compile(goal, 0); // Planning validates content without touching a GUI.
            check(!IntentRuntime.isReadOnlyDesign(goal), "chat requires explicit execute authority");
            var action = AbilityAdapter.adapt(goal, null, null);
            check(action instanceof IntentAction.Native nativeAction && nativeAction.record() instanceof ChatTaskRecord,
                    "chat enters the existing native child-task path");
            var task = TaskFactory.create(null, ((IntentAction.Native) action).record());
            check(task instanceof ChatTask, "chat record registers its runner");
            var cancelled = task.result(TaskState.CANCELLED);
            check(!cancelled.success() && cancelled.interrupted()
                    && cancelled.data().get("submission_attempted").equals(false), "cancellation before start sends nothing");
            check(!RecoveryAdvisor.ordinaryRetryAllowed(cancelled), "human cancellation cannot silently resend");
            var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class);
            compact.setAccessible(true);
            var json = com.google.gson.JsonParser.parseString(cancelled.toJson()).getAsJsonObject();
            var data = ((JsonObject) compact.invoke(null, json)).getAsJsonObject("data");
            check(data.get("delivery_status").getAsString().equals("not_submitted")
                    && !data.get("mechanical_retry_allowed").getAsBoolean(), "Attention preserves submission and retry evidence");
        }
        for (String invalid : List.of("{}", "{\"text\":\"\"}", "{\"text\":42}",
                "{\"text\":\"hello\",\"typing_interval_ms\":1}", "{\"text\":\"hello\",\"keys\":[\"ENTER\"]}")) {
            var parameters = com.google.gson.JsonParser.parseString(invalid).getAsJsonObject();
            try { IntentRuntime.get().compile(goal(parameters), 0); throw new AssertionError("invalid chat plan accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("ChatAbilityTest: passed");
    }

    private static Goal goal(JsonObject parameters) {
        return new Goal("maicraft:chat", "Submit this chat text", null,
                parameters.toString(), "{}", List.of(), List.of());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
