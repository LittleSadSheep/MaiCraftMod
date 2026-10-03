package org.maiwithu.maicraft.task;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 模拟接单、启动和执行失败，核对旧任务交付一次结果后，玩家仍能接着执行下一件事。 */
public final class TaskSlotFailureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            creationFailureReleasesSlot(world.player);
            startFailureSettlesOnce(world.player);
            tickFailureSettlesOnce(world.player);
            cancelSourcesNameTheirOrigin(world.player);
            deliberateDeathRestoresReflexes(world.player);
        }
        TaskDeadlinePauseTest.main(args);
        System.out.println("TaskSlotFailureTest: passed");
    }

    private static void cancelSourcesNameTheirOrigin(LocalPlayer player) {
        // 同一句"任务取消了"可能来自接管、操作者取消或失去身体；回执不写来源，调用方就无法分辨是谁结束了任务。
        List<TaskRecord> completed = new ArrayList<>();
        var slot = new TaskSlot(completed::add);
        TaskFactory.register(TestRecord.class, (body, record) -> new TestTask(false, false));
        var displaced = new TestRecord();
        slot.put(player, displaced);
        var cancelled = new TestRecord();
        slot.put(player, cancelled);
        check(displaced.getState() == TaskState.CANCELLED
                && "takeover".equals(displaced.getCancelSource()), "接管必须把来源记为 takeover");
        check(completed.size() == 1 && completed.get(0) == displaced, "被接管的任务应交付一次结果");
        slot.cancel(player);
        check(cancelled.getState() == TaskState.CANCELLED
                && "operator_cancel".equals(cancelled.getCancelSource()), "操作者取消必须把来源记为 operator_cancel");
        var lost = new TestRecord();
        TaskFactory.register(TestRecord.class, (body, record) -> new NullResultTask());
        slot.put(player, lost);
        slot.bodyGone(player);
        check(lost.getState() == TaskState.CANCELLED
                && "body_gone".equals(lost.getCancelSource()), "失去身体必须把来源记为 body_gone");
        // 执行器交不出结果时走兜底结果；来源也要随兜底结果写进 JSON，不能只停在任务单字段里。
        check(lost.getResult() != null
                && lost.getResult().toJson().contains("cancel_source"), "兜底取消结果应携带 cancel_source 字段");
    }

    private static void deliberateDeathRestoresReflexes(LocalPlayer player) {
        // 显式寻死抢到身体后不能被自救反向接管；暂停、取消和结算死亡都要恢复普通自保顺序。
        List<TaskRecord> completed = new ArrayList<>();
        var slot = new TaskSlot(completed::add);
        var record = new TestRecord();
        boolean[] paused = {false};
        Task reflex = new TestTask(false, false);
        Task deliberate = new Task() {
            @Override public boolean canRun(LocalPlayer body) { return !paused[0]; }
            @Override public boolean suppressesSurvivalReflexes() { return true; }
            @Override public TaskState tick(LocalPlayer body) { return TaskState.RUNNING; }
            @Override public void stop(LocalPlayer body, StopReason reason) { }
            @Override public String name() { return "deliberate_death"; }
            @Override public boolean observeDeath(LocalPlayer body) {
                record.setState(TaskState.SUCCESS); return true;
            }
        };
        TaskFactory.register(TestRecord.class, (body, next) -> deliberate);
        slot.put(player, record);
        check(TaskSelector.select(List.of(reflex), null, deliberate, List.of(), player) == deliberate,
                "寻死执行期间应跳过自救反射");
        paused[0] = true;
        check(TaskSelector.select(List.of(reflex), null, deliberate, List.of(), player) == reflex,
                "暂停寻死后应立即恢复自救");
        check(!slot.observeDeath(player), "活着或断线不能算寻死完成");
        player.setHealth(0);
        check(slot.observeDeath(player) && record.getState() == TaskState.SUCCESS && completed.size() == 1,
                "确认死亡应结清一次成功回执");
        check(!slot.suppressesSurvivalReflexes() && !slot.observeDeath(player), "死亡结算后不能遗留保护豁免");
        player.setHealth(20); paused[0] = false;
        slot.put(player, new TestRecord()); slot.cancel(player);
        check(!slot.suppressesSurvivalReflexes(), "取消后不能遗留保护豁免");
    }

    private static void creationFailureReleasesSlot(LocalPlayer player) {
        // 执行器构造失败还没进入 start，也必须结束任务单，不能留下一张永远等待的单子。
        List<TaskRecord> completed = new ArrayList<>();
        var slot = new TaskSlot(completed::add);
        var failed = new TestRecord();
        TaskFactory.register(TestRecord.class, (body, record) -> {
            throw new IllegalStateException("constructor failed");
        });
        try {
            slot.put(player, failed);
        } catch (RuntimeException escaped) {
            throw new AssertionError("接单异常逃出任务边界，槽位仍占用=" + !slot.isEmpty()
                    + "，任务状态=" + failed.getState(), escaped);
        }
        checkFailedOnce(slot, failed, completed);
        acceptsNextTask(player, slot, completed);
    }

    private static void startFailureSettlesOnce(LocalPlayer player) {
        // 已创建执行器但启动失败时，调用它的收尾一次，再把失败结果交给等待者。
        List<TaskRecord> completed = new ArrayList<>();
        var slot = new TaskSlot(completed::add);
        var failed = new TestRecord();
        var runner = new TestTask(true, false);
        TaskFactory.register(TestRecord.class, (body, record) -> runner);
        slot.put(player, failed);
        checkFailedOnce(slot, failed, completed);
        check(runner.cleanups == 1, "启动失败应只收尾一次");
        acceptsNextTask(player, slot, completed);
    }

    private static void tickFailureSettlesOnce(LocalPlayer player) {
        // 正在干活的执行器失败时也使用同一结束路径，后续调度不能重复交付失败结果。
        List<TaskRecord> completed = new ArrayList<>();
        var slot = new TaskSlot(completed::add);
        var failed = new TestRecord();
        var runner = new TestTask(false, true);
        TaskFactory.register(TestRecord.class, (body, record) -> runner);
        slot.put(player, failed);
        slot.tick(player);
        slot.settleIfTerminal(player);
        checkFailedOnce(slot, failed, completed);
        check(runner.cleanups == 1, "运行失败应只收尾一次");
        acceptsNextTask(player, slot, completed);
    }

    private static void checkFailedOnce(TaskSlot slot, TaskRecord record, List<TaskRecord> completed) {
        // 失败记录必须带结果离开槽位，避免下一次提交时旧记录又被取消一次。
        check(slot.isEmpty(), "失败后应归还任务槽位");
        check(record.getState() == TaskState.FAILED, "失败任务不能停在等待或运行状态");
        check(record.getResult() != null && !record.getResult().success(), "失败任务应交付明确结果");
        check(completed.equals(List.of(record)), "同一失败任务只交付一次");
    }

    private static void acceptsNextTask(LocalPlayer player, TaskSlot slot, List<TaskRecord> completed) {
        // 玩家接着提交正常任务时，应能开始、完成和归还身体，不受上一件失败任务影响。
        TaskFactory.register(TestRecord.class, (body, record) -> new TestTask(false, false));
        var next = new TestRecord();
        slot.put(player, next);
        slot.tick(player);
        check(slot.isEmpty() && next.getState() == TaskState.SUCCESS, "失败后仍能完成下一件任务");
        check(completed.size() == 2 && completed.get(1) == next, "后继任务应单独交付结果");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestRecord extends TaskRecord {
        TestRecord() { super("test_slot", "", NO_DEADLINE); }
    }

    /** 取消路径专用：交不出结果，迫使槽位走兜底结果，验证 cancel_source 一路写进 JSON。 */
    private static final class NullResultTask implements Task {
        @Override public void start(LocalPlayer player) { }
        @Override public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
        @Override public void stop(LocalPlayer player, StopReason reason) { }
        @Override public String name() { return "test_slot"; }
        @Override public TaskResult result(TaskState terminal) { return null; }
    }

    private static final class TestTask implements Task {
        private final boolean failStart;
        private final boolean failTick;
        private int cleanups;

        TestTask(boolean failStart, boolean failTick) {
            this.failStart = failStart;
            this.failTick = failTick;
        }

        // 测试任务分别模拟接单后启动失败、执行失败和正常完成，不执行实际世界修改。
        @Override public void start(LocalPlayer player) {
            if (failStart) throw new IllegalStateException("start failed");
        }
        @Override public TaskState tick(LocalPlayer player) {
            if (failTick) throw new IllegalStateException("tick failed");
            return TaskState.SUCCESS;
        }
        @Override public void stop(LocalPlayer player, StopReason reason) { }
        @Override public String name() { return "test_slot"; }
        @Override public TaskResult result(TaskState terminal) {
            cleanups++;
            return terminal == TaskState.SUCCESS ? TaskResult.ok("done") : TaskResult.fail("failed");
        }
    }
}
