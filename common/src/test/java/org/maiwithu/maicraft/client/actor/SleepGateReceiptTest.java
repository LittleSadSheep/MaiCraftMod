// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.tags.BlockTags;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.task.sleep.SleepCompanionTask;
import org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 睡眠门控与原版可睡窗口同源；入睡自己把整夜跳过时，确认以世界实物判成功而不是按拒绝收场。 */
public final class SleepGateReceiptTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        gateFollowsVanillaWindow();
        honestReceiptCarriesTimeFacts();
        skippedNightProvesSuccess();
        rejectedClickWithoutDayAdvanceStillFails();
        System.out.println("SleepGateReceiptTest: passed");
    }

    /** 120：白天门控的判定边界必须与原版 skyDarken 窗口重合，dusk 相位里可睡的时刻不再被拒。 */
    private static void gateFollowsVanillaWindow() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var time = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(h.level, time);
            registerDimension(h, false);
            // 边界 ±1 刻由原版 Mth.cos 浮点表决定，生产实现逐函数复用原版公式；这里核对
            // wiki 已知的可睡窗口两端（晴朗 12542-23458）两侧的明确归属，窗口内外的相位错判不再发生。
            for (long tick : new long[]{6000L, 12_010L, 12_300L, 1000L, 23_470L})
                check(!sleepableAt(h, time, tick), "clear sky outside the vanilla darkening window rejects sleep: " + tick);
            for (long tick : new long[]{12_542L, 12_795L, 13_564L, 18_000L, 23_458L})
                check(sleepableAt(h, time, tick), "vanilla sleepable ticks pass the gate unchanged: " + tick);
            // 雷暴压低天空变暗值，正午也进入原版可睡条件；公式与原版 updateSkyBrightness 同一份。
            time.setDayTime(6000);
            h.level.setRainLevel(1.0F);
            h.level.setThunderLevel(1.0F);
            check(WorldTimeSemantics.canAttemptSleep(h.level), "a thunderstorm opens the sleep window at noon");
            h.level.setThunderLevel(0.0F);
            h.level.setRainLevel(0.0F);
            // 固定时间维度（下界/末地）在原版里不算白天：门控放行，爆炸风险由床的维度检查另挡。
            registerDimension(h, true);
            check(WorldTimeSemantics.canAttemptSleep(h.level), "a fixed-time dimension is never vanilla-day");
        }
    }

    private static void registerDimension(InteractionWorldTestHarness h, boolean fixedTime) throws Exception {
        var dimension = new DimensionType(fixedTime ? OptionalLong.of(18_000L) : OptionalLong.empty(),
                true, false, false, !fixedTime, 1.0, true, false, 0, 16, 16,
                BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace(fixedTime ? "the_nether" : "overworld"), 0,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
        ActorControlTestHarness.field(Level.class, "dimensionTypeRegistration")
                .set(h.level, Holder.direct(dimension));
    }

    private static boolean sleepableAt(InteractionWorldTestHarness h,
            net.minecraft.client.multiplayer.ClientLevel.ClientLevelData time, long dayTick) throws Exception {
        time.setDayTime(dayTick);
        return WorldTimeSemantics.canAttemptSleep(h.level);
    }

    /** 120：确实不可睡时的决策不再断言含糊的白天，而是给出当前时刻与最近可睡时点。 */
    private static void honestReceiptCarriesTimeFacts() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var time = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(h.level, time);
            registerDimension(h, false);
            Method wait = Class.forName("org.maiwithu.maicraft.intent.AbilityAdapter")
                    .getDeclaredMethod("waitForNightDecision", Goal.class, net.minecraft.client.player.LocalPlayer.class);
            wait.setAccessible(true);
            var goal = new Goal("maicraft:sleep", "fixture", null, "{}", "{}", List.of(), List.of());
            time.setDayTime(12_500);
            var decision = snapshotOf(wait.invoke(null, goal, h.player));
            check(decision.question().contains("time_of_day=12500")
                    && decision.question().contains("in about 42 ticks"),
                    "the decision names the observed tick and the exact wait to the vanilla window");
            check(decision.options().size() == 3, "the decision keeps recover, skip and cancel options");
            time.setDayTime(6000);
            decision = snapshotOf(wait.invoke(null, goal, h.player));
            check(decision.question().contains("time_of_day=6000")
                    && decision.question().contains("in about 6542 ticks"),
                    "a midday decision reports the next window on the following day");
        }
    }

    /** 122：点击后被服务器接受、入睡当场跳到清晨，确认窗只会读到白天——按日指数前进加醒在床边判成功。 */
    private static void skippedNightProvesSuccess() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var time = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(h.level, time);
            registerDimension(h, false);
            placeBed(h);
            time.setDayTime(17 * 24_000L + 13_564);
            var task = new SleepCompanionTask(h.player,
                    new SleepTaskRecord("skip-night", 1_000_000, new BlockPos(0, 1, 1)).untilAwake());
            runUntilTerminal(h, task, true);
            check(task.result(TaskState.SUCCESS).success(), "a night skipped by sleep itself ends the task as success");
            var data = task.result(TaskState.SUCCESS).data();
            check(Boolean.TRUE.equals(data.get("entered_sleep")) && Boolean.TRUE.equals(data.get("night_skipped_by_sleep")),
                    "the success receipt carries the skipped-night evidence for reconciliation");
        }
    }

    /** 122 对照：确认窗过期而世界没有跳夜，仍是失败，不能把普通拒绝误判成已入睡。 */
    private static void rejectedClickWithoutDayAdvanceStillFails() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var time = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(h.level, time);
            registerDimension(h, false);
            placeBed(h);
            time.setDayTime(17 * 24_000L + 13_564);
            var task = new SleepCompanionTask(h.player,
                    new SleepTaskRecord("stuck-night", 1_000_000, new BlockPos(0, 1, 1)).untilAwake());
            runUntilTerminal(h, task, false);
            check(task.result(TaskState.FAILED).message().contains("rejected or not confirmed"),
                    "an unconfirmed click without a day advance stays an honest failure");
            check(!Boolean.TRUE.equals(task.result(TaskState.FAILED).data().get("night_skipped_by_sleep")),
                    "the failure receipt does not claim a skipped night");
        }
    }

    private static void placeBed(InteractionWorldTestHarness h) {
        BlockPos head = new BlockPos(0, 1, 1), foot = head.south();
        var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        h.set(head, state.setValue(BedBlock.PART, BedPart.HEAD)); h.set(foot, state.setValue(BedBlock.PART, BedPart.FOOT));
    }

    /** 注入一次未决的床点击（与入睡任务自己发出的同一端口原语），从确认窗过期处开始回放。 */
    private static void runUntilTerminal(InteractionWorldTestHarness h, SleepCompanionTask task,
            boolean skipNight) throws Exception {
        var context = ClientRuntime.requireContext(h.player);
        var at = new BlockPos(0, 1, 2);
        var receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false),
                NativeConfirmation.pending(), 40);
        ActorControlTestHarness.field(SleepCompanionTask.class, "receipt").set(task, receipt);
        ActorControlTestHarness.field(SleepCompanionTask.class, "clickDayIndex").setLong(task,
                WorldTimeSemantics.dayIndex(h.level));
        for (int i = 0; i < 200; i++) {
            h.nextTick();
            if (skipNight && i == 5) timeAdvanceToMorning(h);
            var state = task.tick(h.player);
            if (state == TaskState.SUCCESS || state == TaskState.FAILED) return;
        }
        throw new AssertionError("sleep task never reached a terminal state");
    }

    private static void timeAdvanceToMorning(InteractionWorldTestHarness h) throws Exception {
        var time = (net.minecraft.client.multiplayer.ClientLevel.ClientLevelData)
                ActorControlTestHarness.field(Level.class, "levelData").get(h.level);
        time.setDayTime(18 * 24_000L + 1000);
    }

    /** IntentAction 是包私有密封接口，这里只借反射取出其中的公开决策快照。 */
    private static IntentTaskRecord.DecisionSnapshot snapshotOf(Object decisionAction) throws Exception {
        return (IntentTaskRecord.DecisionSnapshot) decisionAction.getClass().getMethod("snapshot").invoke(decisionAction);
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
