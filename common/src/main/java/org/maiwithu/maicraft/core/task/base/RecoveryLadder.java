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
     * 一种回退策略。
     *
     * @param strategy    每次进入或重试此策略时创建新的任务；使用 {@link Supplier} 确保重试获得干净实例，且阶梯本身不直接操作 Minecraft。
     * @param handles     此策略愿意处理的 {@link FailureType} 集合；不在集合中的失败原因不会由此策略接管。
     * @param maxAttempts 此策略最多执行次数（≥ 1）；达到上限后前进到下一个匹配策略。
     */
    public record Rung(Supplier<Task> strategy, Set<FailureType> handles, int maxAttempts) {}

    private final List<Rung> rungs;

    /** 当前策略索引；阶梯耗尽时等于 {@code rungs.size()}。 */
    private int index;
    /** 当前策略已提交的执行次数；进入策略时从 1 开始计数。 */
    private int attempts = 1;
    /** 当前策略按需构建的任务；重试或推进时清空，以便重新创建。 */
    private Task cached;

    public RecoveryLadder(List<Rung> rungs) {
        this.rungs = List.copyOf(rungs);
    }

    /** 便捷的可变参数工厂方法。 */
    public static RecoveryLadder of(Rung... rungs) {
        return new RecoveryLadder(List.of(rungs));
    }

    /**
     * 返回当前策略的任务，首次通过 {@link Rung#strategy()} 按需创建，并缓存到阶梯重试或推进为止，因此每刻调用都会复用同一实例。
     * 阶梯 {@link #exhausted()} 后返回 {@code null}。
     */
    // 第一次使用当前策略时才创建任务，并缓存同一个实例；不会每次查询都重新开始。
    public Task current() {
        if (index >= rungs.size()) return null;
        if (cached == null) cached = rungs.get(index).strategy().get();
        return cached;
    }

    /**
     * 根据当前策略的失败原因 {@code lastFail} 决定后续处理。
     * <ul>
     *   <li>若当前策略的 {@link Rung#handles()} 包含 {@code lastFail} 且仍有重试次数（{@code attempts < maxAttempts}），则重建并重试同一策略，返回 {@code true}。</li>
     *   <li>否则前进到后续第一个能处理 {@code lastFail} 的策略，重置其尝试计数并返回 {@code true}。</li>
     *   <li>若没有后续策略能处理此原因，阶梯耗尽并返回 {@code false}；父任务将携带 {@code lastFail} 放弃。</li>
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

    /** 当前策略索引；耗尽后等于策略总数。 */
    public int currentRung() {
        return index;
    }

    /** 当前策略已提交的执行次数，从 1 开始计数。 */
    public int currentAttempt() {
        return attempts;
    }

    /** 没有可执行策略时返回 true。 */
    public boolean exhausted() {
        return index >= rungs.size();
    }
}
