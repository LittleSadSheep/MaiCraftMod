package org.maiwithu.maicraft.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Register a session-local reminder without occupying the player body. */
public final class SetTimerTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_REASON_LENGTH = 200;

    private record Args(int after_s, String reason) {}

    @Override
    public String name() {
        return "set_timer";
    }

    @Override
    public String description() {
        return "Set a one-shot reminder measured in active world time. It is kept in memory, "
                + "does not occupy the body, and is cleared when the local player or world changes. "
                + "task_status lists timers and task_stop cancels one.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("after_s", "Delay in world-time seconds.",
                        TimerRegistry.MIN_SECONDS, TimerRegistry.MAX_SECONDS)
                .string("reason", "What to inspect or decide when the reminder becomes due.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args,
                           LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        String reason = parsed == null || parsed.reason() == null ? "" : parsed.reason().strip();
        if (reason.isEmpty()) {
            reply.accept(TaskResult.fail("reason is required").toJson());
            return;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }

        int asked = parsed.after_s();
        int seconds = TimerRegistry.clampSeconds(asked);
        TimerRegistry registry = TimerRegistry.get();
        long now = player.level().getGameTime();
        TimerRegistry.Timer timer = registry.set(player.getUUID(), now, seconds, reason);
        if (timer == null) {
            reply.accept(TaskResult.fail(
                    "timer limit reached; cancel one first",
                    Map.of("timers", describe(registry.list(player.getUUID()), now))).toJson());
            return;
        }

        reply.accept(TaskResult.ok(
                "set " + timer.id() + " for " + seconds + " seconds: " + reason,
                Map.of("timer_id", timer.id(), "after_s", seconds, "reason", reason)).toJson());
    }

    static String summarize(List<TimerRegistry.Timer> timers, long nowGameTime) {
        if (timers.isEmpty()) {
            return "none";
        }
        StringBuilder summary = new StringBuilder();
        for (TimerRegistry.Timer timer : timers) {
            if (!summary.isEmpty()) {
                summary.append(';');
            }
            summary.append(timer.id()).append(' ')
                    .append(TimerRegistry.remainingSeconds(timer, nowGameTime))
                    .append("s: ").append(timer.reason());
        }
        return summary.toString();
    }

    static List<Map<String, Object>> describe(List<TimerRegistry.Timer> timers, long nowGameTime) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TimerRegistry.Timer timer : timers) {
            result.add(Map.of(
                    "timer_id", timer.id(),
                    "remaining_s", TimerRegistry.remainingSeconds(timer, nowGameTime),
                    "reason", timer.reason()));
        }
        return result;
    }
}
