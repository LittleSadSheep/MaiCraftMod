package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.entity.InputDriver;

import java.util.function.Consumer;

/** 调度器中的一个任务槽，统一处理开始、超时、替换和结束；同时保存记录与实际执行对象。 */
final class TaskSlot {

    private final Consumer<TaskRecord> outbox;

    private Task task;
    private TaskRecord record;
    private long acceptedGameTime = Long.MIN_VALUE;

    TaskSlot(Consumer<TaskRecord> outbox) {
        this.outbox = outbox;
    }

    boolean isEmpty() {
        return record == null;
    }

    boolean freshlyAccepted(LocalPlayer player) {
        return record != null && acceptedGameTime == player.level().getGameTime();
    }

    TaskRecord record() {
        return record;
    }

    boolean canRun(LocalPlayer player) {
        return record != null && record.getState() == TaskState.RUNNING && task.canRun(player);
    }

    /** 新任务先替换并清理旧任务，再调用 start；后续 tick 只有被调度器选中时才推进。 */
    void put(LocalPlayer player, TaskRecord next) {
        if (next == null) {
            throw new IllegalArgumentException("task record is required");
        }
        if (record != null) {
            finishByInterruption(player, Task.StopReason.REPLACED);
        }

        record = next;
        task = TaskFactory.create(player, next);
        acceptedGameTime = player.level().getGameTime();
        next.setState(TaskState.RUNNING);
        next.markStarted(acceptedGameTime);
        try {
            task.start(player);
        } catch (RuntimeException exception) {
            next.setState(TaskState.FAILED);
            runCleanupAfterFailure();
            next.setResult(TaskResult.fail("task start failed: " + safeMessage(exception)));
        }
        settleIfTerminal(player);
    }

    /** Advance this slot once. The brain calls this for the selected winner only. */
    void tick(LocalPlayer player) {
        if (record == null) {
            return;
        }
        if (record.getState() == TaskState.RUNNING) {
            if (player.level().getGameTime() >= record.getDeadlineGameTime()) {
                record.setState(TaskState.TIMEOUT);
            } else {
                try {
                    record.setState(task.tick(player));
                } catch (RuntimeException exception) {
                    record.setState(TaskState.FAILED);
                    runCleanupAfterFailure();
                    record.setResult(TaskResult.fail("task tick failed: " + safeMessage(exception)));
                }
            }
        }
        settleIfTerminal(player);
    }

    void loseBody(LocalPlayer player) {
        if (task != null) {
            task.stop(player, Task.StopReason.PREEMPTED);
        }
    }

    /** Detach a semantic parent for an explicitly authorised body replacement. */
    TaskRecord detachForHandoff(LocalPlayer player) {
        if (record == null) return null;
        try {
            task.stop(player, Task.StopReason.BODY_GONE);
        } catch (RuntimeException ignored) {
            // The semantic record is retained; native child state is deliberately discarded.
        }
        TaskRecord suspended = record;
        task = null;
        record = null;
        acceptedGameTime = Long.MIN_VALUE;
        return suspended;
    }

    /** 等待其他任务使用身体时顺延截止时间，等待调度的时间不计入这个任务的执行预算。 */
    void freeze() {
        if (record != null && record.getState() == TaskState.RUNNING) {
            record.extendDeadlineTo(record.getDeadlineGameTime() + 1L);
        }
    }

    void settleIfTerminal(LocalPlayer player) {
        if (record != null && record.getState().isTerminal()) {
            settle(player);
        }
    }

    boolean cancel(LocalPlayer player) {
        if (record == null) {
            return false;
        }
        finishByInterruption(player, Task.StopReason.REPLACED);
        return true;
    }

    void bodyGone(LocalPlayer player) {
        if (record != null) {
            finishByInterruption(player, Task.StopReason.BODY_GONE);
        }
    }

    private void finishByInterruption(LocalPlayer player, Task.StopReason reason) {
        try {
            task.stop(player, reason);
        } catch (RuntimeException ignored) {
            // result/cleanup below is still the authoritative wind-down path
        }
        if (!record.getState().isTerminal()) {
            record.setState(TaskState.CANCELLED);
        }
        settle(player);
    }

    private void runCleanupAfterFailure() {
        try {
            task.result(TaskState.FAILED);
        } catch (RuntimeException ignored) {
            // The explicit framework failure result is still delivered below.
        }
    }

    private void settle(LocalPlayer player) {
        // 无论结果生成是否抛异常，都要归还输入并清空槽位；清理后才把最终记录交给结果队列。
        TaskRecord finished = record;
        try {
            if (finished.getResult() == null) {
                try {
                    finished.setResult(task.result(finished.getState()));
                } catch (RuntimeException exception) {
                    finished.setResult(TaskResult.fail("task result failed: " + safeMessage(exception)));
                }
            }
            if (finished.getResult() == null) {
                finished.setResult(defaultResult(finished.getState()));
            }
        } finally {
            releaseBody(player);
            task = null;
            record = null;
            acceptedGameTime = Long.MIN_VALUE;
        }
        outbox.accept(finished);
    }

    /** A terminal slot can never leave a movement lease behind, even if result cleanup throws. */
    private static void releaseBody(LocalPlayer player) {
        try {
            InputDriver.halt(player);
        } catch (RuntimeException ignoredFailure) {
        }
        try {
            ClientRuntime.requireContext(player).body().releaseAll();
        } catch (RuntimeException ignoredFailure) {
        }
    }

    private static TaskResult defaultResult(TaskState state) {
        return switch (state) {
            case SUCCESS -> TaskResult.ok("done");
            case TIMEOUT -> TaskResult.timeout("timed out");
            case CANCELLED -> TaskResult.cancelled("cancelled");
            default -> TaskResult.fail("task failed without a result");
        };
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
