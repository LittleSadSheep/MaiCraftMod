// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 只负责按顺序组合已有建造与原生过程，逐刻转发生命周期；不复制建造、投料或GUI操作逻辑。 */
final class NativeProcessTask implements Task {
    private final LocalPlayer player;
    private final NativeProcessTaskRecord record;
    private final Supplier<TaskRecord> processFactory;
    private final BiFunction<LocalPlayer, TaskRecord, Task> tasks;
    private final LongSupplier clock;
    private final BooleanSupplier currentWorld;
    private Task child;
    private TaskRecord childRecord;
    private boolean constructionDone, processStarted;
    private TaskState terminal;
    private TaskResult constructionResult, processResult;

    NativeProcessTask(LocalPlayer player, NativeProcessTaskRecord record) {
        this(player, record, () -> NativeProcessRegistry.createTask(record.getToolCallId() + "-process",
                record.getDeadlineGameTime(), player, record.anchor, record.request), TaskFactory::create,
                () -> player.level().getGameTime(), () -> player.level().dimension().location().toString().equals(record.dimension));
    }
    NativeProcessTask(LocalPlayer player, NativeProcessTaskRecord record, Supplier<TaskRecord> processFactory,
                      BiFunction<LocalPlayer, TaskRecord, Task> tasks, LongSupplier clock, BooleanSupplier currentWorld) {
        this.player = player; this.record = record; this.processFactory = processFactory;
        this.tasks = tasks; this.clock = clock; this.currentWorld = currentWorld;
        constructionDone = record.construction == null;
    }

    @Override public TaskState tick(LocalPlayer ignored) {
        if (terminal != null) return terminal;
        try {
            if (!currentWorld.getAsBoolean()) return fail("native_process_world_changed");
            if (child == null) {
                childRecord = constructionDone ? processFactory.get() : record.construction;
                if (constructionDone) {
                    if (!(childRecord instanceof NativeSubmissionTaskRecord consumption)
                            || !consumption.submissionNamespace().equals(record.submissionNamespace()))
                        throw new IllegalStateException("native process child must preserve its consumption namespace");
                    // 子任务再次等待的是包装任务同一屏障；这里绝不能返回固定true，也不能创建第二个消费编号。
                    consumption.submissionBarrier(record::prepareSubmission); processStarted = true;
                }
                child = tasks.apply(player, childRecord); childRecord.setState(TaskState.RUNNING); childRecord.markStarted(clock.getAsLong());
                child.start(player);
            }
            TaskState state = childRecord.getState().isTerminal() ? childRecord.getState()
                    : clock.getAsLong() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : child.tick(player);
            record.extendDeadlineTo(childRecord.getDeadlineGameTime());
            if (state == null || !state.isTerminal()) return TaskState.RUNNING;
            TaskResult result = settleChild(state, StopReason.REPLACED);
            if (state != TaskState.SUCCESS || !result.success()) { terminal = state == TaskState.SUCCESS ? TaskState.FAILED : state; return terminal; }
            if (!constructionDone) { constructionDone = true; return TaskState.RUNNING; }
            record.verified(); terminal = TaskState.SUCCESS; return terminal;
        } catch (RuntimeException failure) {
            return fail("native_process_failed: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private TaskResult settleChild(TaskState state, StopReason reason) {
        Task finishing = child; TaskRecord finishingRecord = childRecord;
        TaskResult result;
        try {
            // 即使停止动作抛错，也必须继续调用result收取原生回执与清理，不能把悬空菜单留给下一阶段。
            if (state != TaskState.SUCCESS) try { finishing.stop(player, reason); } catch (RuntimeException ignoredStopFailure) { }
            result = finishing.result(state);
            if (result == null) result = uncertain("native process child returned no effect receipt");
        } catch (RuntimeException failure) {
            result = uncertain("native process child cleanup could not confirm its effects");
        } finally { child = null; childRecord = null; }
        finishingRecord.setState(state); finishingRecord.setResult(result);
        if (constructionDone) processResult = result; else constructionResult = result;
        return result;
    }
    private TaskState fail(String message) {
        if (child != null) settleChild(TaskState.FAILED, StopReason.REPLACED);
        if (processResult == null) processResult = constructionResult == null ? TaskResult.fail(message)
                : new TaskResult(false, message, constructionResult.timedOut(), constructionResult.interrupted(), constructionResult.data());
        terminal = TaskState.FAILED; return terminal;
    }
    private static TaskResult uncertain(String message) {
        return TaskResult.fail(message, Map.of("outcome_uncertain", true, "mechanical_retry_allowed", false));
    }

    @Override public void stop(LocalPlayer ignored, StopReason reason) {
        if (child == null) return;
        // 生存抢占保留原子任务对象；取消和离开世界才结算并释放，后续result不能再重复清理同一个子任务。
        if (reason == StopReason.PREEMPTED) { child.stop(player, reason); return; }
        settleChild(childRecord.getState().isTerminal() ? childRecord.getState() : TaskState.CANCELLED, reason);
        if (terminal == null) terminal = TaskState.CANCELLED;
    }
    @Override public TaskResult result(TaskState state) {
        if (child != null) settleChild(state == TaskState.SUCCESS ? TaskState.CANCELLED : state, StopReason.REPLACED);
        TaskResult detail = processResult != null ? processResult : constructionResult;
        var data = new LinkedHashMap<String, Object>(detail == null ? Map.of() : detail.data());
        data.put("process", record.request.process()); data.put("construction_completed", constructionDone);
        if (constructionResult != null) data.put("construction", constructionResult.data());
        data.put("native_consumption_reserved", record.submissionReserved());
        if (record.submissionReserved()) data.put("mechanical_retry_allowed", false);
        boolean success = state == TaskState.SUCCESS && terminal == TaskState.SUCCESS && processResult != null && processResult.success();
        return new TaskResult(success, detail == null ? "native process stopped before a result was available" : detail.message(),
                state == TaskState.TIMEOUT || detail != null && detail.timedOut(),
                state == TaskState.CANCELLED || detail != null && detail.interrupted(), data);
    }
    @Override public String name() { return "native_process"; }
    @Override public Map<String, Object> progress() {
        var data = new LinkedHashMap<String, Object>(child == null ? Map.of() : child.progress());
        data.put("process", record.request.process());
        data.put("process_stage", terminal != null ? "complete" : !constructionDone ? "construction" : processStarted ? "processing" : "preparing_process");
        data.put("native_consumption_reserved", record.submissionReserved()); return data;
    }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return child != null && child.mustSettleBeforeSatisfiedCancellation(); }
    @Override public void requestSatisfiedSettlement() { if (child != null) child.requestSatisfiedSettlement(); }
}
