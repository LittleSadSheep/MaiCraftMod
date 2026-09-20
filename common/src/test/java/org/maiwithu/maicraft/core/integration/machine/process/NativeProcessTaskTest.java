// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;

/** 用记录型子任务检查建造包装的顺序、消费屏障与生命周期，不操作方块、菜单或投料。 */
public final class NativeProcessTaskTest {
    private static final BlockPos PROTECTED = new BlockPos(4, 2, 4);
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        constructionThenSharedBarrier(); pauseCancelAndTimeout(); failedConstructionAndNamespace(); constructionRetrySafety();
        System.out.println("NativeProcessTaskTest: shared barrier, child lifecycle, timeout and inherited protection passed");
    }

    private static void constructionThenSharedBarrier() {
        var build = new PlainRecord(100); var operation = new ConsumerRecord("enchant", 100);
        var builder = new Probe(); builder.next = TaskState.SUCCESS; builder.protectedContext = true;
        var worker = new Probe(); worker.protectedContext = true;
        var ready = new AtomicBoolean(); var barrierCalls = new AtomicInteger(); var factoryCalls = new AtomicInteger();
        var record = wrapper(build); record.submissionBarrier(() -> { barrierCalls.incrementAndGet(); return ready.get(); });
        worker.action = () -> operation.prepareSubmission() ? TaskState.SUCCESS : TaskState.RUNNING;
        var task = new NativeProcessTask(null, record, () -> { factoryCalls.incrementAndGet(); return operation; },
                (player, child) -> child == build ? builder : worker, () -> 1, () -> true);
        var cells = new LongOpenHashSet(); cells.add(PROTECTED.asLong());
        NavigationSafetyContext.withProtectedArea(cells, cells, () -> {
            check(task.tick(null) == TaskState.RUNNING && builder.results == 1 && factoryCalls.get() == 0,
                    "建造已确认后也要等下一刻才创建加工子任务");
            check(task.tick(null) == TaskState.RUNNING && barrierCalls.get() == 1 && record.submissionReserved()
                    && operation.submissionReserved(), "子任务必须等待包装任务同一消费屏障");
            check(worker.starts == 1 && task.progress().get("marker").equals("child-progress"), "逐刻转发原生子任务进度");
            ready.set(true);
            check(task.tick(null) == TaskState.SUCCESS && worker.starts == 1 && barrierCalls.get() == 2,
                    "预约完成后继续原子任务，不重新创建或绕过屏障");
            var result = task.result(TaskState.SUCCESS); task.result(TaskState.SUCCESS);
            check(result.success() && builder.results == 1 && worker.results == 1 && record.internalVerifiedPosition() != null,
                    "只有建造与原生过程都完成才成功，结果读取不重复收尾");
            check(Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed")), "已消费的包装结果保留重试禁令");
            return null;
        });
    }

    private static void pauseCancelAndTimeout() {
        var build = new PlainRecord(100); var builder = new Probe(); var record = wrapper(build);
        var task = new NativeProcessTask(null, record, () -> { throw new AssertionError("paused build cannot start a process"); },
                (player, child) -> builder, () -> 1, () -> true);
        task.tick(null); task.stop(null, Task.StopReason.PREEMPTED); task.tick(null);
        check(builder.starts == 1 && builder.stops == 1 && builder.results == 0, "暂停后恢复同一子任务，不丢回执或重新开始");
        builder.throwOnStop = true;
        task.stop(null, Task.StopReason.REPLACED); task.result(TaskState.CANCELLED); task.result(TaskState.CANCELLED);
        check(builder.results == 1 && task.tick(null) == TaskState.CANCELLED, "即使stop抛错也要收取result，取消后不能重建子任务");
        var clock = new long[]{1}; var timedBuild = new PlainRecord(2); var timed = new Probe();
        var timeout = new NativeProcessTask(null, wrapper(timedBuild), () -> { throw new AssertionError("timed-out build cannot run production"); },
                (player, child) -> timed, () -> clock[0], () -> true);
        timeout.tick(null); clock[0] = 2;
        check(timeout.tick(null) == TaskState.TIMEOUT && timeout.result(TaskState.TIMEOUT).timedOut()
                && timed.stops == 1 && timed.results == 1, "包装必须执行子任务真实截止时间并且只结算一次");
    }

    private static void failedConstructionAndNamespace() {
        var build = new PlainRecord(100); var failed = new Probe(); failed.next = TaskState.FAILED;
        var task = new NativeProcessTask(null, wrapper(build), () -> { throw new AssertionError("failed construction cannot start process"); },
                (player, child) -> failed, () -> 1, () -> true);
        check(task.tick(null) == TaskState.FAILED && !task.result(TaskState.FAILED).success() && failed.results == 1, "建造失败时不运行原生消费");
        var wrong = new NativeProcessTask(null, wrapper(null), () -> new ConsumerRecord("world-process", 100),
                (player, child) -> { throw new AssertionError("wrong namespace cannot start"); }, () -> 1, () -> true);
        check(wrong.tick(null) == TaskState.FAILED, "包装不得把另一命名空间的子任务接到旧附魔预约");
    }

    private static void constructionRetrySafety() {
        // 加工尚未预约时也必须保留施工桶的真实提交结果，取消收尾和普通失败使用相同回执语义。
        for (TaskState state : new TaskState[]{TaskState.FAILED, TaskState.TIMEOUT, TaskState.CANCELLED}) {
            var uncertain = constructionFailureReceipt(true, state);
            check(Boolean.TRUE.equals(uncertain.data().get("outcome_uncertain"))
                    && Boolean.FALSE.equals(uncertain.data().get("mechanical_retry_allowed")), "桶提交未知时不能被包装清除重试禁令");
            var unsubmitted = constructionFailureReceipt(false, state);
            check(Boolean.FALSE.equals(unsubmitted.data().get("outcome_uncertain"))
                    && Boolean.TRUE.equals(unsubmitted.data().get("mechanical_retry_allowed")), "未提交桶的施工失败仍保留重新观察后继续的资格");
        }
    }

    public static TaskResult constructionFailureReceipt(boolean submitted, TaskState state) {
        // 模拟MachineBuildTask已经透传到顶层的桶回执；真实施工中的回执收集由流体装配回归另行验证。
        var build = new PlainRecord(100); var builder = new Probe(); builder.next = state;
        var effects = Map.<String, Object>of("bucket_submitted", submitted, "outcome_uncertain", submitted,
                "mechanical_retry_allowed", !submitted);
        builder.receipt = new TaskResult(false, "fixture bucket placement stopped", state == TaskState.TIMEOUT,
                state == TaskState.CANCELLED, effects);
        var record = wrapper(build);
        var task = new NativeProcessTask(null, record, () -> { throw new AssertionError("failed construction cannot consume process inputs"); },
                (player, child) -> builder, () -> 1, () -> true);
        if (state == TaskState.CANCELLED) {
            builder.next = TaskState.RUNNING; task.tick(null); task.stop(null, Task.StopReason.REPLACED);
        } else check(task.tick(null) == state, "施工终态必须透传给包装");
        var result = task.result(state);
        check(!result.success() && !record.submissionReserved()
                && Boolean.FALSE.equals(result.data().get("native_consumption_reserved")) && builder.results == 1,
                "施工失败不触发加工预约，且只收取一次原生回执");
        return result;
    }

    private static NativeProcessTaskRecord wrapper(TaskRecord construction) {
        var request = NativeProcessRequest.parse(JsonParser.parseString("{\"schema_version\":2,\"process\":\"minecraft:enchanting\","
                + "\"parameters\":{\"item_id\":\"minecraft:book\",\"max_levels_spent\":1,\"max_lapis\":1}}").getAsJsonObject());
        return new NativeProcessTaskRecord("wrapper-test", 100, request, BlockPos.ZERO, "minecraft:overworld", construction);
    }
    private static final class PlainRecord extends TaskRecord { PlainRecord(long deadline) { super("construction-probe", "probe", deadline); } }
    private static final class ConsumerRecord extends NativeSubmissionTaskRecord {
        ConsumerRecord(String namespace, long deadline) { super("native-probe", "probe", deadline, namespace); }
    }
    private static final class Probe implements Task {
        int starts, stops, results;
        boolean throwOnStop, protectedContext;
        TaskState next = TaskState.RUNNING;
        TaskResult receipt;
        Supplier<TaskState> action;
        private void protection() {
            if (protectedContext) check(NavigationSafetyContext.protectsMutation(PROTECTED) && NavigationSafetyContext.protectsUse(PROTECTED)
                    && NavigationSafetyContext.forbiddenBodyCells().contains(PROTECTED.asLong()), "子任务实际保留父任务的修改、使用和身体通行保护");
        }
        @Override public void start(LocalPlayer player) { protection(); starts++; }
        @Override public TaskState tick(LocalPlayer player) { protection(); return action == null ? next : action.get(); }
        @Override public void stop(LocalPlayer player, StopReason reason) {
            protection(); stops++; if (throwOnStop) throw new IllegalStateException("fixture stop failure");
        }
        @Override public TaskResult result(TaskState state) { protection(); results++; return receipt != null ? receipt : state == TaskState.SUCCESS ? TaskResult.ok("child completed") : TaskResult.fail("child stopped"); }
        @Override public String name() { return "lifecycle-probe"; }
        @Override public Map<String, Object> progress() { return Map.of("marker", "child-progress"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
