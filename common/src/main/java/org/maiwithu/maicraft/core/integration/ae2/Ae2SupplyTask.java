// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 把供料会话接入任务调度，转达成功、失败与实际变化；阶段有推进或仍有已提交事务时延长等待时间。
 */
final class Ae2SupplyTask implements Task {
    private static final long PROGRESS_LEASE_TICKS = 3L * 60L * 20L;
    private final LocalPlayer player;
    private final Ae2SupplyTaskRecord record;
    private Ae2ResourceSupply.Session session;
    private Ae2ResourceSupply.Outcome outcome;
    private TaskState terminal;
    private String earlyFailure;
    private String observedPhase;

    Ae2SupplyTask(LocalPlayer player, Ae2SupplyTaskRecord record) {
        this.player = player;
        this.record = record;
    }

    @Override
    public void start(LocalPlayer companion) {
        if (!Ae2ResourceSupply.available()) {
            earlyFailure = "AE2 client integration is unavailable: "
                    + Ae2ResourceSupply.availabilityDetail();
            terminal = TaskState.FAILED;
            record.setState(TaskState.FAILED);
            return;
        }
        try {
            session = Ae2ResourceSupply.begin(player, record.request);
        } catch (RuntimeException failure) {
            earlyFailure = "could not start AE2 resource supply: " + failure.getMessage();
            terminal = TaskState.FAILED;
            record.setState(TaskState.FAILED);
        }
    }

    @Override
    public TaskState tick(LocalPlayer companion) {
        if (terminal != null) return terminal;
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        var settled = session.tick(context);
        String phase = session.phase();
        if (!phase.equals(observedPhase) || session.livenessActive()) {
            record.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
            observedPhase = phase;
        }
        if (settled.isEmpty()) return TaskState.RUNNING;
        outcome = settled.orElseThrow();
        terminal = switch (outcome.status()) {
            case SUCCEEDED -> TaskState.SUCCESS;
            case CANCELLED -> TaskState.CANCELLED;
            default -> TaskState.FAILED;
        };
        return terminal;
    }

    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        if (session == null || terminal != null) return;
        try {
            LocalPlayerContext context = ClientRuntime.requireContext(player);
            if (why == StopReason.PREEMPTED) {
                session.pause(context);
            } else {
                outcome = session.cancel(context,
                        why == StopReason.BODY_GONE
                                ? "the local-player body disappeared during AE2 supply"
                                : "AE2 resource supply was replaced by another task");
                terminal = outcome.status() == Ae2ResourceSupply.Status.CANCELLED
                        ? TaskState.CANCELLED : TaskState.FAILED;
            }
        } catch (RuntimeException unavailable) {
            if (why != StopReason.PREEMPTED) {
                earlyFailure = "AE2 resource supply ended while its actor context was unavailable";
                terminal = TaskState.CANCELLED;
            }
        }
    }

    @Override
    public TaskResult result(TaskState finalState) {
        if (finalState == TaskState.TIMEOUT && outcome == null && session != null) {
            try {
                outcome = session.cancel(
                        ClientRuntime.requireContext(player),
                        "AE2 resource supply timed out before its first-person cleanup completed");
            } catch (RuntimeException unavailable) {
                earlyFailure = "AE2 resource supply timed out while actor cleanup was unavailable";
            }
        }
        Map<String, Object> data = outcome == null ? Map.of() : outcome.data();
        String message = outcome != null ? outcome.message()
                : earlyFailure != null ? earlyFailure : "AE2 resource supply did not settle";
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(message, data);
            case TIMEOUT -> new TaskResult(false,
                    "AE2 resource supply timed out; do not repeat if the prior result was uncertain",
                    true, false, data);
            case CANCELLED -> new TaskResult(false, message, false, true, data);
            default -> TaskResult.fail(message, data);
        };
    }

    @Override
    public String name() {
        return "Ae2SupplyTask";
    }
}
