// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.chain.NightRestChain;
import org.maiwithu.maicraft.core.task.sleep.NightRestTask;
import org.maiwithu.maicraft.core.task.sleep.SleepCompanionTask;
import org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 夜间休息要等真正醒来；隔墙、占床与未确认点击都不能被“附近有床”这一事实掩盖。 */
public final class NightRestBehaviorTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        waitsForWake(); ordinaryWorkOnly(); bedEligibilityAndWall(); unsettledAction();
        defensePreservesReturnBudget();
        System.out.println("NightRestBehaviorTest: passed");
    }
    private static void waitsForWake() throws Exception {
        for (boolean morning : List.of(false, true)) try (var h = new InteractionWorldTestHarness()) {
            // 昼夜直接注入原版世界数据，不能覆盖共享夹具的 getDayTime 而屏蔽其他等待条件测试。
            var time = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(h.level, time); time.setDayTime(14000);
            SleepSafetyTest.bedWorks(h, true);
            ActorControlTestHarness.field(h.player.getClass(), "sleeping").setBoolean(h.player, true);
            var task = new SleepCompanionTask(h.player, new SleepTaskRecord("whole-night", 1000, new BlockPos(1, 1, 1)).untilAwake());
            check(task.tick(h.player) == TaskState.RUNNING && h.blockUses() == 0, "lying down holds the rest task without another bed click");
            h.nextTick(); check(task.tick(h.player) == TaskState.RUNNING, "ongoing sleep cannot release the body to construction");
            ActorControlTestHarness.field(h.player.getClass(), "sleeping").setBoolean(h.player, false);
            check(task.tick(h.player) == TaskState.RUNNING, "a wake-up packet before the time packet is not an immediate failure");
            for (int i = 0; i < 10; i++) h.nextTick();
            if (morning) time.setDayTime(1000);
            else for (int i = 0; i < 200; i++) h.nextTick();
            check(task.tick(h.player) == (morning ? TaskState.SUCCESS : TaskState.FAILED), "a delayed morning packet succeeds while unchanged night fails after a bounded wait");
            check(Boolean.valueOf(morning).equals(task.result(morning ? TaskState.SUCCESS : TaskState.FAILED).data().get("morning_observed")),
                    "the receipt distinguishes observed sleep, waking and actual morning");
        }
    }
    private static void ordinaryWorkOnly() throws Exception {
        Method allowed = NightRestChain.class.getDeclaredMethod("ordinaryWork", TaskRecord.class); allowed.setAccessible(true);
        for (String ability : List.of("maicraft:build_machine", "maicraft:acquire_items", "maicraft:combat", "maicraft:wait", "maicraft:fish", "maicraft:follow")) {
            var goal = new Goal(ability, "fixture", null, "{}", "{}", List.of(), List.of());
            var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            check((boolean) allowed.invoke(null, task) == (ability.equals("maicraft:build_machine") || ability.equals("maicraft:acquire_items")),
                    "routine rest cannot skip explicitly timed or active night activities: " + ability);
        }
    }
    private static void bedEligibilityAndWall() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            SleepSafetyTest.bedWorks(h, true);
            BlockPos head = new BlockPos(0, 1, 1), foot = head.south();
            var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
            h.set(head, state.setValue(BedBlock.PART, BedPart.HEAD)); h.set(foot, state.setValue(BedBlock.PART, BedPart.FOOT));
            Method usable = NightRestChain.class.getDeclaredMethod("usable", LocalPlayer.class, BlockPos.class); usable.setAccessible(true);
            check((boolean) usable.invoke(null, h.player, head), "a loaded complete unoccupied bed may be used");
            check(!NavigationSafetyContext.withProtectedArea(List.of(head), List.of(), () -> {
                try { return (boolean) usable.invoke(null, h.player, head); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }), "protected beds remain excluded");
            h.set(head, h.level.getBlockState(head).setValue(BedBlock.OCCUPIED, true));
            check(!(boolean) usable.invoke(null, h.player, head), "occupied beds are not repeatedly clicked");
            h.set(head, h.level.getBlockState(head).setValue(BedBlock.OCCUPIED, false));
            var task = new NightRestTask(h.player, new NightRestTask.Record("wall", 1000, h.player.blockPosition(), head));
            Method reachable = NightRestTask.class.getDeclaredMethod("canReachBed"); reachable.setAccessible(true);
            check((boolean) reachable.invoke(task), "a close visible bed is genuinely in reach");
            h.set(new BlockPos(0, 2, 2), Blocks.STONE.defaultBlockState());
            check(!(boolean) reachable.invoke(task), "the same distance behind a wall still requires navigation");
        }
    }
    private static void unsettledAction() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var context = ClientRuntime.requireContext(h.player); var at = new BlockPos(0, 0, 3);
            check(ClientRuntime.actor().settledForRoutinePause(), "an idle body allows a routine pause");
            var receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false), NativeConfirmation.pending(), 40);
            check(!ClientRuntime.actor().settledForRoutinePause(), "rest cannot interrupt a pending native world action");
            context.actions().retireOneShotForTaskBoundary(context, receipt, "fixture ended");
            check(ClientRuntime.actor().settledForRoutinePause(), "settled world actions permit the next routine boundary");
        }
    }
    private static void defensePreservesReturnBudget() throws Exception {
        // 醒后返回途中自卫占用身体超过原期限；恢复仍先执行返工收尾，不能直接把角色留在撤离终点。
        for (boolean cancel : List.of(false, true)) try (var h = new InteractionWorldTestHarness()) {
            var chain = new NightRestChain();
            long start = h.level.getGameTime();
            var record = new NightRestTask.Record("preempted-rest", start + 10, h.player.blockPosition(), new BlockPos(1, 1, 1));
            var rest = new NightRestTask(h.player, record); rest.start(h.player);
            ActorControlTestHarness.field(NightRestTask.class, "returning").setBoolean(rest, true);
            ActorControlTestHarness.field(NightRestChain.class, "record").set(chain, record);
            ActorControlTestHarness.field(NightRestChain.class, "rest").set(chain, rest);
            chain.stop(h.player, Task.StopReason.PREEMPTED);
            for (int i = 0; i < 20; i++) h.nextTick();
            chain.stop(h.player, Task.StopReason.PREEMPTED);
            for (int i = 0; i < 20; i++) h.nextTick();
            if (cancel) chain.stop(h.player, Task.StopReason.REPLACED);
            else chain.tick(h.player);
            check(record.getDeadlineGameTime() == start + (cancel ? 10 : 50),
                    "only resumed rest receives its actual preemption duration, once");
            check(ActorControlTestHarness.field(NightRestChain.class, "rest").get(chain) == null,
                    "a resumed return at its origin can settle normally, and cancellation still terminates");
            check(ActorControlTestHarness.field(NightRestChain.class, "preemptedAt").getLong(chain) == -1,
                    "terminal rest cannot donate its suspension time to another night");
        }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
