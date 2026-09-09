package org.maiwithu.maicraft.core.task.base;

import org.maiwithu.maicraft.task.TaskState;

import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.core.FailureType;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 保存几种可依次尝试的任务策略，根据失败类型和尝试次数选择下一种。
 * 当前生产源码没有构造它的调用点；现有任务里的重试并不会因为改了这里而自动改变。
 */
public final class RecoveryLadder {

    /**
     * One fallback approach.
     *
     * @param strategy    builds a fresh task for this rung each time it is
     *                    (re)entered — a {@link Supplier} so a retry gets a clean
     *                    instance and the ladder never touches Minecraft itself.
     * @param handles     the {@link FailureType}s this rung is willing to catch;
     *                    a cause outside this set is not handled by this rung.
     * @param maxAttempts total number of executions allowed on this rung
     *                    (≥ 1); reaching it advances to the next matching rung.
     */
    public record Rung(Supplier<Task> strategy, Set<FailureType> handles, int maxAttempts) {}

    private final List<Rung> rungs;

    /** Index of the current rung; {@code == rungs.size()} once exhausted. */
    private int index;
    /** Executions committed on the current rung so far (starts at 1 when a rung is entered). */
    private int attempts = 1;
    /** The lazily-built task for the current rung; nulled on retry / advance so it is rebuilt. */
    private Task cached;

    public RecoveryLadder(List<Rung> rungs) {
        this.rungs = List.copyOf(rungs);
    }

    /** Convenience varargs factory. */
    public static RecoveryLadder of(Rung... rungs) {
        return new RecoveryLadder(List.of(rungs));
    }

    /**
     * The task for the current rung, lazily built via its {@link Rung#strategy()}
     * and cached until the ladder retries or advances (so per-tick calls reuse the
     * same instance). {@code null} once the ladder is {@link #exhausted()}.
     */
    // 第一次使用当前策略时才创建任务，并缓存同一个实例；不会每次查询都重新开始。
    public Task current() {
        if (index >= rungs.size()) return null;
        if (cached == null) cached = rungs.get(index).strategy().get();
        return cached;
    }

    /**
     * Decide what to do after the current rung failed with {@code lastFail}.
     * <ul>
     *   <li>If the current rung {@link Rung#handles() handles} {@code lastFail}
     *       and it has attempts left ({@code attempts < maxAttempts}): retry the
     *       SAME rung (rebuild its strategy) and return {@code true}.</li>
     *   <li>Otherwise advance to the next LATER rung whose {@code handles}
     *       contains {@code lastFail}, reset its attempt counter, and return
     *       {@code true}.</li>
     *   <li>If neither applies — no remaining rung handles the cause — the ladder
     *       is exhausted: return {@code false} (the parent gives up carrying
     *       {@code lastFail}).</li>
     * </ul>
     */
    // 当前策略接受这类失败且次数没满就再试；否则往后找能处理这种失败的策略。
    // 找不到就耗尽。它只换策略引用，旧任务的停止和清理仍由调用方负责。
    public boolean advance(FailureType lastFail) {
        if (index < rungs.size()) {
            Rung r = rungs.get(index);
            if (r.handles().contains(lastFail) && attempts < r.maxAttempts()) {
                attempts++;
                cached = null;         // rebuild the strategy for the retry
                return true;
            }
        }
        for (int i = index + 1; i < rungs.size(); i++) {
            if (rungs.get(i).handles().contains(lastFail)) {
                index = i;
                attempts = 1;
                cached = null;
                return true;
            }
        }
        index = rungs.size();          // exhausted
        cached = null;
        return false;
    }

    /** Index of the current rung (or the rung count once exhausted). */
    public int currentRung() {
        return index;
    }

    /** Executions committed on the current rung so far (1-based). */
    public int currentAttempt() {
        return attempts;
    }

    /** True once no rung remains to run. */
    public boolean exhausted() {
        return index >= rungs.size();
    }
}
