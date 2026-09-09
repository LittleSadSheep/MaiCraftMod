package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.entity.InputDriver;

import java.util.function.Consumer;

/** 保存一件正在做的事：记录“要做什么、做完没有”，并让对应的执行代码每次往前做一点。 */
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
        // 只有已经开始、还没结束的任务才能继续；任务也可以说“现在做不了”，暂时让别人执行。
        return record != null && record.getState() == TaskState.RUNNING && task.canRun(player);
    }

    /** 换一件事做：先结束旧任务，再准备新任务；真正往下做，要等调度器选到它。 */
    void put(LocalPlayer player, TaskRecord next) {
        if (next == null) {
            throw new IllegalArgumentException("task record is required");
        }
        if (record != null) {
            finishByInterruption(player, Task.StopReason.REPLACED);
        }

        record = next;
        // 先记下新任务，再找负责做这件事的代码；如果“找执行代码”这一步抛异常，下面的 try 接不住。
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

    /** 调度器这次选到它，才检查是否超时，并让任务往前做一步。 */
    void tick(LocalPlayer player) {
        if (record == null) {
            return;
        }
        if (record.getState() == TaskState.RUNNING) {
            // 先看是否超时，再让任务执行。因此一旦已超时，任务连“我还在正常前进，请延长时间”都来不及说。
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
        // 例如正在挖矿时要先躲怪：先停下操作，但记住矿挖到哪了，回来继续，不从头开始。
        if (task != null) {
            task.stop(player, Task.StopReason.PREEMPTED);
        }
    }

    /** 过传送门时取出总任务，让新世界里的玩家接着做。 */
    TaskRecord detachForHandoff(LocalPlayer player) {
        // 只记住目标和已完成的步骤；旧世界的路线、打开的菜单等不能拿到新世界接着用。
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

    /** 这次轮不到它做事，就把截止时间往后推一刻，避免“光等别人干活也算超时”。 */
    void freeze() {
        // 这里只延长总任务的时间；总任务里面正在做的“小任务”不会跟着延长。
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
        // 如果事情已经做完，就保留原结果；迟到的“取消”不能把已经成功的事说成没做完。
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
        // result 不只负责回答结果，还负责关菜单、停导航等收尾；即使收尾出错，也要报告任务失败。
        try {
            task.result(TaskState.FAILED);
        } catch (RuntimeException ignored) {
            // The explicit framework failure result is still delivered below.
        }
    }

    private void settle(LocalPlayer player) {
        // 不管能否顺利生成结果，都先松开自动控制的按键、移走旧任务，再把结果交出去。
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

    /** 任务结束后不能还按着前进或潜行；即使某一步松键失败，也继续尝试其他停止操作。 */
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
