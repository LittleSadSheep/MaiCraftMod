// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.outcome.Outcome;

import java.util.Objects;

/**
 * 内核持有的任务生命周期：编号、任务单、状态、结果与起止时刻。
 *
 * <p>状态只能按 PENDING → RUNNING → FINISHED 前进，还没开始就被取消时可以从 PENDING 直接结束；
 * 终态不可再改。非法迁移直接抛异常——那说明调用方的逻辑写错了，不是游戏里发生了什么，
 * 不能像 v1 那样任由任何状态改成任何状态。
 *
 * <p>只在客户端主线程读写，本类不加锁。
 */
public final class TaskHandle {
    private final long id;
    private final TaskRecord record;
    private TaskState state = TaskState.PENDING;
    private Outcome outcome;
    private long startedTick = -1;
    private long finishedTick = -1;

    /** 编号由分配任务的服务给出（例如目标存储），这里不持有全局计数器。 */
    public TaskHandle(long id, TaskRecord record) {
        this.id = id;
        this.record = Objects.requireNonNull(record, "record");
    }

    /** 第一次被推进时调用：从 PENDING 进入 RUNNING。 */
    public void start(long gameTick) {
        if (state != TaskState.PENDING) {
            throw new IllegalStateException("任务 " + id + " 只能从 PENDING 开始，当前是 " + state);
        }
        state = TaskState.RUNNING;
        startedTick = gameTick;
    }

    /** 结束并记下回执；还没开始就被取消时允许从 PENDING 直接结束。 */
    public void finish(Outcome result, long gameTick) {
        if (state == TaskState.FINISHED) {
            throw new IllegalStateException("任务 " + id + " 已经结束，不能再改结果");
        }
        outcome = Objects.requireNonNull(result, "result");
        state = TaskState.FINISHED;
        finishedTick = gameTick;
    }

    public long id() {
        return id;
    }

    public TaskRecord record() {
        return record;
    }

    public TaskState state() {
        return state;
    }

    /** 结束后的回执；还没结束时为 null。 */
    public Outcome outcome() {
        return outcome;
    }

    /** 开始的游戏刻；还没开始时为 -1。 */
    public long startedTick() {
        return startedTick;
    }

    /** 结束的游戏刻；还没结束时为 -1。 */
    public long finishedTick() {
        return finishedTick;
    }
}
