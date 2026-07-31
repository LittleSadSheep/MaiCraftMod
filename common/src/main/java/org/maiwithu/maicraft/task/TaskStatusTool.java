package org.maiwithu.maicraft.task;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Instant local query for occupied task slots and session timers. */
public final class TaskStatusTool implements MaiCraftTool {

    @Override
    public String name() {
        return "task_status";
    }

    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return "Read the current local-player tasks and pending in-memory timers.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object().build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args,
                           LocalPlayer player, Consumer<String> reply) {
        long now = player.level().getGameTime();
        List<TaskRecord> records = CompanionTickDispatcher.list();
        List<TimerRegistry.Timer> timers = TimerRegistry.get().list(player.getUUID());

        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> taskData = new ArrayList<>();
        for (TaskRecord record : records) {
            long elapsed = record.getStartedGameTime() < 0
                    ? 0L
                    : Math.max(0L, now - record.getStartedGameTime()) / 20L;
            boolean standing = record.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE;
            long budget = standing
                    ? -1L
                    : Math.max(0L, record.getDeadlineGameTime() - now) / 20L;
            taskData.add(Map.of(
                    "task_id", record.publicId(),
                    "task", record.getToolName(),
                    "state", record.getState().name().toLowerCase(),
                    "elapsed_s", elapsed,
                    "budget_left_s", budget,
                    "description", record.describe()));
        }
        if (!taskData.isEmpty()) {
            data.put("tasks", taskData);
        }
        if (!timers.isEmpty()) {
            data.put("timers", SetTimerTool.describe(timers, now));
        }

        String taskSummary = records.isEmpty()
                ? "body idle"
                : records.size() + " task slot(s) occupied";
        String timerSummary = timers.isEmpty()
                ? "no timers"
                : timers.size() + " timer(s): " + SetTimerTool.summarize(timers, now);
        reply.accept(TaskResult.ok(taskSummary + "; " + timerSummary, data).toJson());
    }
}
