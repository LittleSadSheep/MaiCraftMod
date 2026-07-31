package org.maiwithu.maicraft.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Instant local cancellation for a task slot or timer. */
public final class TaskStopTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();

    private record Args(String task_id) {}

    @Override
    public String name() {
        return "task_stop";
    }

    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return "Cancel a local-player task (t...) or in-memory timer (tm...). "
                + "Omit task_id to cancel the current background task.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("task_id", "Task id (t...) or timer id (tm...).")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args,
                           LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        String wanted = parsed == null || parsed.task_id() == null || parsed.task_id().isBlank()
                ? null
                : parsed.task_id().strip();
        long now = player.level().getGameTime();

        if (wanted != null && TimerRegistry.get().cancel(player.getUUID(), wanted)) {
            reply.accept(TaskResult.ok("cancelled timer " + wanted,
                    Map.of("timer_id", wanted)).toJson());
            return;
        }

        TaskRecord target = wanted == null
                ? CompanionTickDispatcher.current()
                : CompanionTickDispatcher.find(wanted);
        if (target == null) {
            reply.accept(TaskResult.fail(nothingMatched(wanted, player, now)).toJson());
            return;
        }

        CompanionTickDispatcher.cancel(target.publicId());
        reply.accept(TaskResult.ok("cancelled " + target.publicId(),
                Map.of("task_id", target.publicId())).toJson());
    }

    private static String nothingMatched(String wanted, LocalPlayer player, long now) {
        List<TaskRecord> tasks = CompanionTickDispatcher.list();
        List<TimerRegistry.Timer> timers = TimerRegistry.get().list(player.getUUID());
        String taskSummary = tasks.isEmpty()
                ? "body idle"
                : tasks.stream().map(TaskRecord::publicId).toList().toString();
        String pending = taskSummary + "; timers: " + SetTimerTool.summarize(timers, now);
        return wanted == null
                ? "there is no current background task; " + pending
                : "no task or timer named " + wanted + "; " + pending;
    }
}
