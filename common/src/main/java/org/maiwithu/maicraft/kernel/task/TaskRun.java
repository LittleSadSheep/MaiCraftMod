// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.Objects;

/**
 * 一次任务运行的记录：编号、输入、状态、结果与起止时刻。由内核持有，LLM 查询任务时看到的就是它。
 *
 * <p>状态只能按 PENDING → RUNNING → FINISHED 前进，还没开始就被取消时可以从 PENDING 直接结束；
 * 结束后不可再改。违反顺序直接抛异常：那说明调用方的代码写错了，不是游戏里发生了什么。
 *
 * <p>只在客户端主线程读写，本类不加锁。
 */
public final class TaskRun {
    private final long id;
    private final TaskInput input;
    private TaskState state = TaskState.PENDING;
    private TaskResult result;
    private long startedTick = -1;
    private long finishedTick = -1;

    /** 编号由分配任务的一方给出（例如任务存储），这里不持有全局计数器。 */
    public TaskRun(long id, TaskInput input) {
        this.id = id;
        this.input = Objects.requireNonNull(input, "input");
    }

    /** 第一次被推进时调用：从 PENDING 进入 RUNNING。 */
    public void start(long gameTick) {
        if (state != TaskState.PENDING) {
            throw new IllegalStateException("任务 " + id + " 只能从 PENDING 开始，当前是 " + state);
        }
        state = TaskState.RUNNING;
        startedTick = gameTick;
    }

    /** 结束并记下结果；还没开始就被取消时允许从 PENDING 直接结束。 */
    public void finish(TaskResult value, long gameTick) {
        if (state == TaskState.FINISHED) {
            throw new IllegalStateException("任务 " + id + " 已经结束，不能再改结果");
        }
        result = Objects.requireNonNull(value, "value");
        state = TaskState.FINISHED;
        finishedTick = gameTick;
    }

    public long id() {
        return id;
    }

    public TaskInput input() {
        return input;
    }

    public TaskState state() {
        return state;
    }

    /** 结束后的结果；还没结束时为 null。 */
    public TaskResult result() {
        return result;
    }

    /** 开始时的游戏刻；还没开始时为 -1。 */
    public long startedTick() {
        return startedTick;
    }

    /** 结束时的游戏刻；还没结束时为 -1。 */
    public long finishedTick() {
        return finishedTick;
    }
}
