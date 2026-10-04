// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.command;

import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 人工接管清理指令：核对清理入口结束占用槽位的任务并交付带来源的取消回执，
 * 空闲身体不被误动，清理后的身体能立即接受新任务。
 */
public final class ManualTakeoverClearTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            clearsRunningTaskAndDeliversCancelledReceipt(world.player);
            idleBodyReportsNothingToClear(world.player);
        }
        System.out.println("ManualTakeoverClearTest: passed");
    }

    private static void clearsRunningTaskAndDeliversCancelledReceipt(LocalPlayer player) {
        TaskFactory.register(TestRecord.class, (body, record) -> new RunningTask());
        var record = new TestRecord();
        CompanionTickDispatcher.submitCurrent(player, record);
        check(CompanionTickDispatcher.list().size() == 1, "前置：任务应已占用当前槽位");

        var outcome = MaiCraftClearTaskCommand.clearOnClient(player);
        check(outcome.cleared() && outcome.detail().contains(record.publicId()),
                "清理成功必须报告被清任务的编号");
        check(record.getState() == TaskState.CANCELLED
                && "operator_cancel".equals(record.getCancelSource()),
                "人工清理必须把任务结算为取消，来源记为操作者取消");
        check(record.getResult() != null && !record.getResult().success()
                && record.getResult().toJson().contains("cancel_source"),
                "被清任务应交付带取消来源的结果，供等待中的调用接收");
        check(CompanionTickDispatcher.list().isEmpty(), "清理后任务槽必须归还");

        // 清理的目的是人工接管后 AI 停手，身体不能因此失去接新任务的能力。
        var next = new TestRecord();
        CompanionTickDispatcher.submitCurrent(player, next);
        check(next.getState() == TaskState.RUNNING, "清理后的身体应能立即接受新任务");
        var again = MaiCraftClearTaskCommand.clearOnClient(player);
        check(again.cleared() && again.detail().contains(next.publicId()),
                "后继任务同样应能被清理");
        check(CompanionTickDispatcher.list().isEmpty(), "二次清理后任务槽同样归还");
    }

    private static void idleBodyReportsNothingToClear(LocalPlayer player) {
        var outcome = MaiCraftClearTaskCommand.clearOnClient(player);
        check(!outcome.cleared() && outcome.detail().contains("没有正在执行的任务"),
                "空闲身体应报告无任务可清");
        check(CompanionTickDispatcher.list().isEmpty(), "空闲清理不得留下任务记录");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestRecord extends TaskRecord {
        TestRecord() { super("test_clear", "", NO_DEADLINE); }
    }

    /** 一直运行的任务：交不出结果，迫使槽位走兜底取消结果。 */
    private static final class RunningTask implements Task {
        @Override public void start(LocalPlayer player) { }
        @Override public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
        @Override public void stop(LocalPlayer player, StopReason reason) { }
        @Override public String name() { return "test_clear"; }
        @Override public TaskResult result(TaskState terminal) { return null; }
    }
}
