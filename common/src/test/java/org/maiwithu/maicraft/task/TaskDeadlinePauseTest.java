package org.maiwithu.maicraft.task;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 自卫抢占时间长于内部建造预算时，恢复的子任务仍有剩余时间；旁边槽位和后来出生的任务不多补。 */
public final class TaskDeadlinePauseTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var runners = new ArrayList<Composite>();
            TaskFactory.register(Root.class, (player, record) -> {
                var runner = new Composite(player); runners.add(runner); return runner;
            });
            TaskFactory.register(Leaf.class, (player, record) -> new Idle());
            var first = new TaskSlot(ignored -> {}); var second = new TaskSlot(ignored -> {});
            long start = world.level.getGameTime();
            var root = new Root(start + 10000);
            first.put(world.player, root); first.tick(world.player);
            second.put(world.player, new Root(start + 10000)); second.tick(world.player);
            var work = runners.getFirst(); var independent = runners.get(1);
            var outside = new Leaf(start + 20);
            long preparedDeadline = work.prepared.getDeadlineGameTime();
            // 相当于防卫占用七十五秒，超过子施工的一秒示例预算；根任务和每一层子单只补一次。
            for (int tick = 0; tick < 1500; tick++) { advance(world, 1); first.freeze(); }
            check(root.getDeadlineGameTime() == start + 11500, "root is extended exactly once per suspended tick");
            for (var record : work.records()) check(record.getDeadlineGameTime() == start + 1520,
                    "children created during construction, start and tick inherit the same pause");
            check(work.prepared.getDeadlineGameTime() == preparedDeadline + 1500,
                    "a precompiled child joins the parent clock when its runner is created");
            check(outside.getDeadlineGameTime() == start + 20
                    && independent.started.getDeadlineGameTime() == start + 20,
                    "unrelated records and another task slot never inherit this pause");
            first.tick(world.player);
            check(!work.expired && work.afterResume.getDeadlineGameTime() == world.level.getGameTime() + 20,
                    "resuming can drive the child, while a newly created child receives no historical pause credit");
            // 显式进展续期使用当前绝对时间；之后的新抢占仍只加实际暂停长度。
            work.started.extendDeadlineTo(world.level.getGameTime() + 50);
            for (int tick = 0; tick < 30; tick++) { advance(world, 1); first.freeze(); }
            check(work.started.getDeadlineGameTime() == world.level.getGameTime() + 50,
                    "progress extensions and pause extensions do not double-count");
            check(work.afterResume.getDeadlineGameTime() == world.level.getGameTime() + 20,
                    "children born after resuming inherit only later pauses");
            advance(world, 51); first.tick(world.player);
            check(work.expired, "normal active elapsed time can still expire a child budget");
            // 即使接单构造抛异常，作用域也必须退出，后续记录不能串到失败任务的时钟。
            TaskFactory.register(Root.class, (player, record) -> { throw new IllegalStateException("scope fixture"); });
            var broken = new Root(world.level.getGameTime() + 100); new TaskSlot(ignored -> {}).put(world.player, broken);
            check(TaskDeadlineClock.active() == null, "exceptional task creation releases the clock scope");
            var unlimited = new Leaf(Long.MAX_VALUE); unlimited.freezeDeadline();
            check(unlimited.getDeadlineGameTime() == Long.MAX_VALUE, "unbounded budgets saturate without wrapping");
        }
        System.out.println("TaskDeadlinePauseTest: nested deadline suspension and isolation passed");
    }

    private static final class Root extends TaskRecord {
        Root(long deadline) { super("pause_root", "", deadline); }
    }
    private static final class Leaf extends TaskRecord {
        Leaf(long deadline) { super("pause_leaf", "", deadline); }
    }
    private static class Idle implements Task {
        @Override public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
        @Override public void stop(LocalPlayer player, StopReason reason) { }
        @Override public String name() { return "pause fixture"; }
    }
    private static final class Composite extends Idle {
        final Leaf constructed;
        final Leaf prepared;
        Leaf started, ticked, afterResume;
        int ticks; boolean expired;
        Composite(LocalPlayer player) {
            constructed = new Leaf(player.level().getGameTime() + 20);
            // 模拟先于当前作用域编译的任务单，再通过真实任务工厂交给父任务。
            prepared = new Leaf(player.level().getGameTime() + 20); prepared.isolateDeadlineClock();
        }
        @Override public void start(LocalPlayer player) { started = new Leaf(player.level().getGameTime() + 20); }
        @Override public TaskState tick(LocalPlayer player) {
            if (ticks++ == 0) {
                ticked = new Leaf(player.level().getGameTime() + 20); TaskFactory.create(player, prepared);
            } else {
                expired = player.level().getGameTime() >= started.getDeadlineGameTime();
                if (afterResume == null) afterResume = new Leaf(player.level().getGameTime() + 20);
            }
            return TaskState.RUNNING;
        }
        List<Leaf> records() { return List.of(constructed, prepared, started, ticked); }
    }
    // 只推进夹具的游戏时钟，模拟本能占用身体；不向真实 Minecraft 发命令。
    private static void advance(InteractionWorldTestHarness world, long ticks) throws Exception {
        var clock = world.level.getClass().getDeclaredField("time"); clock.setAccessible(true);
        clock.setLong(world.level, world.level.getGameTime() + ticks);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
