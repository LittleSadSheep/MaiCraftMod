package org.maiwithu.maicraft.task;

/** 同一总任务及其子任务共用暂停计数；自卫、其他槽位和后续新任务各自计时。 */
final class TaskDeadlineClock {
    private static final ThreadLocal<TaskDeadlineClock> ACTIVE = new ThreadLocal<>();
    long pausedTicks;

    static TaskDeadlineClock active() { return ACTIVE.get(); }
    static TaskDeadlineClock inherit() {
        TaskDeadlineClock current = active();
        return current == null ? new TaskDeadlineClock() : current;
    }
    Scope enter() { return new Scope(this); }

    // 子任务可能在构造、启动或逐刻推进中产生；离开这些调用后立即恢复原作用域，避免串到自卫任务。
    static final class Scope implements AutoCloseable {
        private final TaskDeadlineClock previous;
        Scope(TaskDeadlineClock clock) { previous = ACTIVE.get(); ACTIVE.set(clock); }
        @Override public void close() {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
}
