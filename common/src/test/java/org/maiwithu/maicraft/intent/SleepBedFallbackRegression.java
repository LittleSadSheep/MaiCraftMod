// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 166：携带床时 sleep 先凑现成床，现成床被遮挡、够不着或失效不再终局失败，
 * 而是回退就近放下自带床并入睡；回执如实记录用了自带床，无自带床维持诚实失败。
 */
public final class SleepBedFallbackRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 夹具环境不加载原版数据包标签；睡眠翻译按 BlockTags.BEDS 找床，这里手工绑定同一标签。
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.bindTags(java.util.Map.of(
                BlockTags.BEDS, List.of(net.minecraft.world.level.block.Blocks.WHITE_BED.builtInRegistryHolder())));
        usableIndexedBedAnchorsTheChain();
        unusableIndexedBedIsNotChased();
        unusableIndexedBedFallsThroughToCarriedBed();
        occludedVillageBedFallsBackToCarriedBed();
        explicitBedTargetNeverSwapsBeds();
        noCarriedBedKeepsHonestFailure();
        emptyWorldPlansCarriedBedDirectly();
        fallbackChainFailureReportsBothAttempts();
        unreachableBedFailsHonestly();
        placementSiteRequiresInteractiveStance();
        fallbackSuccessReceiptNamesTheCarriedBed();
        gateDecisionKindMarksTheDoor();
        gateRelayThreeStates();
        recoverConsumesIntoWindowWait();
        skipAnswerStaysGeneric();
        gateWaitSurvivesCheckpoint();
        System.out.println("SleepBedFallbackRegression: passed");
    }

    /**
     * 177：索引命中的可用床仍是行动锚——链上 goto 与 sleep 都用床的真实坐标。
     * 索引命中在使用前必须过活世界复验，这是复验不误伤正常床的对照面。
     */
    private static void usableIndexedBedAnchorsTheChain() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            placeBedAt(world, new net.minecraft.core.BlockPos(4, 1, 4),
                    net.minecraft.core.Direction.EAST);
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            // 冷索引一次查询预算内走不完：生产中翻译每刻重跑，这里同样推进到查询收敛。
            Object action = invokeSleep(world, goal);
            for (int i = 0; i < 50 && action == IntentAction.Pending.INSTANCE; i++) {
                world.nextTick();
                action = invokeSleep(world, goal);
            }
            check(action instanceof IntentAction.Chain,
                    "a grounded indexed bed still anchors the goto-then-sleep chain: " + action);
            List<?> actions = ((IntentAction.Chain) action).actions();
            check("goto".equals(((IntentAction.Tool) actions.get(0)).toolName())
                            && "sleep".equals(((IntentAction.Tool) actions.get(1)).toolName()),
                    "the usable-bed chain is goto then sleep");
            var sleepArgs = JsonParser.parseString(
                    ((IntentAction.Tool) actions.get(1)).argumentsJson()).getAsJsonObject();
            check(sleepArgs.get("y").getAsInt() == 1,
                    "the usable-bed chain targets the bed's real height, not a stale one: " + sleepArgs);
        }
    }

    /**
     * 177：索引命中悬空（无支撑、床边无站立格）时不交给导航——寻路器对这样的目标带
     * 只会空转停滞，角色零位移；无自带床时如实交回决定，不假装“范围里没有床”。
     */
    private static void unusableIndexedBedIsNotChased() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            placeBedAt(world, new net.minecraft.core.BlockPos(4, 8, 4),
                    net.minecraft.core.Direction.EAST);
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            Object action = invokeSleep(world, goal);
            for (int i = 0; i < 50 && action == IntentAction.Pending.INSTANCE; i++) {
                world.nextTick();
                action = invokeSleep(world, goal);
            }
            check(!(action instanceof IntentAction.Chain),
                    "a floating indexed bed is never handed to navigation");
            check(action instanceof IntentAction.Decision
                            && ((IntentAction.Decision) action).snapshot().question()
                                    .contains("currently approachable"),
                    "an unusable bed hit ends in an honest decision: " + action);
        }
    }

    /** 悬空命中不追，但背包里有床时照常回退到就地放床，不再额外多问一轮。 */
    private static void unusableIndexedBedFallsThroughToCarriedBed() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            placeBedAt(world, new net.minecraft.core.BlockPos(4, 8, 4),
                    net.minecraft.core.Direction.EAST);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            Object action = invokeSleep(world, goal);
            for (int i = 0; i < 50 && action == IntentAction.Pending.INSTANCE; i++) {
                world.nextTick();
                action = invokeSleep(world, goal);
            }
            check(action instanceof IntentAction.Chain,
                    "a carried bed still plans place-then-sleep past an unusable hit");
            assertCarriedBedChain((IntentAction.Chain) action, "minecraft:white_bed");
        }
    }

    /** 在指定格放一张完整的床（床头按朝向自动补齐），不落支撑。 */
    private static void placeBedAt(InteractionWorldTestHarness world,
            net.minecraft.core.BlockPos foot, net.minecraft.core.Direction facing) {
        var footState = net.minecraft.world.level.block.Blocks.WHITE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, facing);
        var headState = net.minecraft.world.level.block.Blocks.WHITE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, facing);
        world.set(foot, footState);
        world.set(foot.relative(facing), headState);
    }

    /** 反射调用私有 sleep 翻译：与 emptyWorldPlansCarriedBedDirectly 同一入口。 */
    private static Object invokeSleep(InteractionWorldTestHarness world, Goal goal) throws Exception {
        Method sleep = AbilityAdapter.class.getDeclaredMethod("sleep",
                Goal.class, net.minecraft.client.player.LocalPlayer.class, IntentRuntime.class);
        sleep.setAccessible(true);
        return sleep.invoke(null, goal, world.player, null);
    }

    /** ①现成床被遮挡：回退链是 放自带床 → 走到预选站位 → 按床头坐标入睡。 */
    private static void occludedVillageBedFallsBackToCarriedBed() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var goal = new Goal("maicraft:sleep", "sleep through the night", null, "{}", "{}", List.of(), List.of());
            AbilityAdapter.CarriedBedPlan plan = AbilityAdapter.carriedBedFallback(goal, world.player, "occluded");
            check(plan != null && plan.action() instanceof IntentAction.Chain,
                    "an occluded village bed with a carried bed engages the fallback chain");
            assertCarriedBedChain((IntentAction.Chain) plan.action(), "minecraft:white_bed");
            check(plan.bedHead() != null && plan.bedHead().equals(sleepCoordsOf((IntentAction.Chain) plan.action())),
                    "the fallback plan hands the anchored bed head to the task layer");
        }
    }

    /** target 指定床区时调用方点名了那张床：被遮挡也如实失败，不悄悄换床。 */
    private static void explicitBedTargetNeverSwapsBeds() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var goal = new Goal("maicraft:sleep", "sleep at the camp",
                    new Goal.SemanticTarget("coordinates", null,
                            new Goal.WorldPosition(2, 1, 2, "minecraft:overworld"), null),
                    "{}", "{}", List.of(), List.of());
            check(AbilityAdapter.carriedBedFallback(goal, world.player, "occluded") == null,
                    "an explicitly targeted bed is never swapped for the carried one");
            // 失败类型之外的原因（有怪、白天拒绝等）不构成回退理由。
            var plain = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            check(AbilityAdapter.carriedBedFallback(plain, world.player, "unknown") == null,
                    "failures that are not about bed availability do not trigger the fallback");
        }
    }

    /** ③背包没有床：现成床被遮挡维持原失败，不虚构任何出路。 */
    private static void noCarriedBedKeepsHonestFailure() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            check(AbilityAdapter.carriedBedFallback(goal, world.player, "occluded") == null,
                    "without a carried bed the original honest failure stays");
        }
    }

    /** ②世界里没有任何现成床：翻译直接产出放自带床的链，不走先凑现成床的弯路。 */
    private static void emptyWorldPlansCarriedBedDirectly() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            Method sleep = AbilityAdapter.class.getDeclaredMethod("sleep",
                    Goal.class, net.minecraft.client.player.LocalPlayer.class, IntentRuntime.class);
            sleep.setAccessible(true);
            Object action = sleep.invoke(null, goal, world.player, null);
            check(action instanceof IntentAction.Chain,
                    "an empty world with a carried bed plans place-then-sleep directly");
            assertCarriedBedChain((IntentAction.Chain) action, "minecraft:white_bed");
        }
    }

    /** 回退链再次判遮挡：先换到床边可交互站位重试一次（锚定同一张床头），第三次才诚实终局。 */
    private static void fallbackChainFailureReportsBothAttempts() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var task = task(world);
            failStep(task, world, TaskResult.fail("bed is occluded from the current stance",
                    Map.of("failure_type", "occluded")));
            var chain = chainOf(task);
            check(chain.size() == 3 && "build".equals(chain.get(0).toolName()),
                    "the first failure engages the carried-bed fallback chain");
            var anchoredHead = sleepCoordsOf(new IntentAction.Chain(chain));
            // 换位重试的选址读真实床方块：按 build 步的落位把床放进测试世界。
            placeBedFromBuildStep(world, (IntentAction.Tool) chain.get(0));
            var reposition = failStep(task, world,
                    TaskResult.fail("bed is occluded from the current stance",
                            Map.of("failure_type", "occluded")));
            check(reposition == TaskState.RUNNING, "an occluded placed bed repositions instead of ending the task");
            var retryChain = chainOf(task);
            check(retryChain.size() == 2 && "goto".equals(retryChain.get(0).toolName())
                            && "sleep".equals(retryChain.get(1).toolName()),
                    "the reposition retry is goto then sleep");
            check(sleepCoordsOf(new IntentAction.Chain(retryChain)).equals(anchoredHead),
                    "the reposition retry sleeps in the same anchored bed, not a rescan");
            var state = failStep(task, world,
                    TaskResult.fail("bed is occluded from the current stance",
                            Map.of("failure_type", "occluded")));
            check(state == TaskState.FAILED, "a third failure after the single reposition ends the task");
            check(resultMessage(task).contains("carried-bed fallback also failed")
                            && resultMessage(task).contains("occluded from the current stance"),
                    "the terminal message carries both the fallback failure and the earlier village-bed failure");
            int wrapCount = resultMessage(task).split("carried-bed fallback also failed", -1).length - 1;
            check(wrapCount == 1, "the two-part failure is not double-wrapped by the retry: " + resultMessage(task));
        }
    }

    /** 床边没有可站立换位（床头信息已失效）：遮挡不再重试，如实终局。 */
    private static void unreachableBedFailsHonestly() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var task = task(world);
            failStep(task, world, TaskResult.fail("bed is occluded from the current stance",
                    Map.of("failure_type", "occluded")));
            // 床头锚点失效（床已被破坏）：footOf 读不到床方块，换位无从谈起。
            field(IntentTask.class, "bedFallbackBedHead").set(task,
                    new net.minecraft.core.BlockPos(3, 1, 3));
            var state = failStep(task, world, TaskResult.fail("bed is occluded from the current stance",
                    Map.of("failure_type", "occluded")));
            check(state == TaskState.FAILED, "a stale anchored bed ends the task honestly");
            check(resultMessage(task).contains("carried-bed fallback also failed"),
                    "the honest terminal still names both failures");
        }
    }

    /** 选址预核交互：地形被石墙抬高、相邻格多被堵死时，选出的落位仍带可站立、视线通床头的站位。 */
    private static void placementSiteRequiresInteractiveStance() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            // 把玩家西侧一圈立起石墙：首圈候选 (7,1,7)/床头 (7,1,8) 的全部相邻格都被堵死，
            // 选址必须跳过它，落到一个带可交互站位的落位上。
            for (int x = 6; x <= 8; x++) {
                for (int z = 6; z <= 9; z++) {
                    world.set(new net.minecraft.core.BlockPos(x, 1, z),
                            x == 8 && z == 8 ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                                    : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    world.set(new net.minecraft.core.BlockPos(x, 2, z),
                            x == 8 && z == 8 ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                                    : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                }
            }
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            AbilityAdapter.CarriedBedPlan plan = AbilityAdapter.carriedBedFallback(goal, world.player, "occluded");
            check(plan != null && plan.action() instanceof IntentAction.Chain,
                    "a walled-off first ring still finds an interactive placement site");
            IntentAction.Chain chain = (IntentAction.Chain) plan.action();
            assertCarriedBedChain(chain, "minecraft:white_bed");
        }
    }

    /** 回退链睡成：步骤回执带 used_carried_bed_fallback 与换床原因，回退标记随后清账。 */
    private static void fallbackSuccessReceiptNamesTheCarriedBed() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var task = task(world);
            failStep(task, world, TaskResult.fail("bed is occluded from the current stance",
                    Map.of("failure_type", "occluded")));
            setChain(task, List.of(new IntentAction.Tool("sleep", "{}")));
            Method finish = IntentTask.class.getDeclaredMethod("finishToolSuccess", TaskResult.class);
            finish.setAccessible(true);
            TaskState state = (TaskState) finish.invoke(task, TaskResult.ok("sleeping in bed"));
            check(state == TaskState.SUCCESS, "a completed fallback chain completes the whole sleep task");
            var steps = record(task).stepResults();
            check(steps.size() == 1 && steps.getLast().success(), "the sleep step is recorded as success");
            String receipt = steps.getLast().result().toString();
            check(receipt.contains("used_carried_bed_fallback")
                            && receipt.contains("village_bed_fallback_reason")
                            && receipt.contains("occluded from the current stance"),
                    "the success receipt names the carried bed and why the village bed was abandoned: " + receipt);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 145 批六B：白天门决策的发射侧标记——快照 context 带 decision_kind=sleep_day_gate，
     * 语义层据此认出这扇门、把 recover 消费成等待而不是重问同一扇关着的门。
     */
    private static void gateDecisionKindMarksTheDoor() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            day(world);
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            var decision = snapshotOf(AbilityAdapter.waitForNightDecision(goal, world.player));
            check(IntentTask.isSleepGateDecision(decision), "the sleep day gate decision carries the gate kind");
            check(decision.context().get("time_of_day") != null
                    && decision.context().get("next_sleepable_in_ticks") != null,
                    "the gate context keeps the time facts for receipt reconciliation");
            var generic = new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(), "choose",
                    List.of(new IntentTaskRecord.DecisionOption("recover", "d")), "{}");
            check(!IntentTask.isSleepGateDecision(generic), "a context without the kind is an ordinary decision");
        }
    }

    /** 三态中继与 explore 兴趣中继同一语义：等答复 / 已答门按住重发 / 新决策照发。 */
    private static void gateRelayThreeStates() {
        check(IntentTask.sleepGateRelayAction(true, true, false) == IntentTask.SleepGateRelayAction.PARK,
                "an open decision parks until the answer arrives");
        check(IntentTask.sleepGateRelayAction(false, true, true) == IntentTask.SleepGateRelayAction.HOLD_REISSUE,
                "an answered gate in its wait leg must not re-issue the same closed gate");
        check(IntentTask.sleepGateRelayAction(false, true, false) == IntentTask.SleepGateRelayAction.ISSUE,
                "a fresh gate encounter issues the decision exactly once");
        check(IntentTask.sleepGateRelayAction(false, false, false) == IntentTask.SleepGateRelayAction.ISSUE,
                "ordinary decisions keep the generic path");
    }

    /** recover 消费后转入等待腿：同门换新编号的重提被按住，窗口打开后才恢复睡觉步骤。 */
    private static void recoverConsumesIntoWindowWait() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            day(world);
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            var snapshot = snapshotOf(AbilityAdapter.waitForNightDecision(goal, world.player));
            var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            check(!record.sleepGateWaiting(), "a fresh task is not waiting for the sleep window");
            record.requestDecision(snapshot, 1);
            check(record.answer(snapshot.id(), "recover", new JsonObject()),
                    "the gate accepts a recover answer without details.goal");
            check("recover".equals(record.takeAnswer().choice()), "the answer is consumed once");
            record.armSleepGateWait();
            check(record.sleepGateWaiting(), "the consumed recover answer arms the window wait");
            // 同门换新编号再问被按住——实机三连 4123f34f→c885717c→d3697403 的回归锚点。
            var reissue = snapshotOf(AbilityAdapter.waitForNightDecision(goal, world.player));
            check(IntentTask.sleepGateRelayAction(record.decisionSnapshot() != null,
                            IntentTask.isSleepGateDecision(reissue), record.sleepGateWaiting())
                    == IntentTask.SleepGateRelayAction.HOLD_REISSUE,
                    "the same closed gate gets no new decision id while the wait leg holds");
            check(!WorldTimeSemantics.canAttemptSleep(world.level), "the daytime world keeps the window closed");
            night(world);
            check(WorldTimeSemantics.canAttemptSleep(world.level), "night opens the window");
            record.disarmSleepGateWait();
            check(!record.sleepGateWaiting(), "an open window disarms the wait and the sleep step re-plans");
        }
    }

    /** skip 与 cancel 走通用消费分支：skip 一次消费成功、两种答复都不进入等待腿。 */
    private static void skipAnswerStaysGeneric() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            day(world);
            var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
            var snapshot = snapshotOf(AbilityAdapter.waitForNightDecision(goal, world.player));
            var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            record.requestDecision(snapshot, 1);
            check(record.answer(snapshot.id(), "skip", new JsonObject()), "skip is an accepted gate choice");
            check("skip".equals(record.takeAnswer().choice()), "the skip answer is consumed once");
            check(!record.sleepGateWaiting(), "skip never arms the window wait");
            var cancelRecord = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            cancelRecord.requestDecision(snapshot, 1);
            check(cancelRecord.answer(snapshot.id(), "cancel", new JsonObject()),
                    "cancel is an accepted gate choice");
            check(!cancelRecord.sleepGateWaiting(), "cancel never arms the window wait");
        }
    }

    /** 等待腿随检查点持久化：重启恢复后同一扇已答复的门继续等窗口，不重提。 */
    private static void gateWaitSurvivesCheckpoint() {
        var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.armSleepGateWait();
        var encoded = IntentStateCodec.encode("sleep-gate-test", List.of(), List.of(record), Map.of(), List.of());
        check(encoded.getAsJsonArray("tasks").get(0).getAsJsonObject().get("sleep_gate_waiting").getAsBoolean(),
                "the armed window wait persists with the checkpoint");
        var snapshot = IntentStateCodec.decode(encoded).tasks().getFirst();
        var restored = IntentTaskRecord.restored(snapshot.id(), snapshot.planId(), snapshot.goal(),
                "sleep-gate-test", snapshot.steps(), snapshot.stepIndex(), snapshot.completed(),
                snapshot.internalPositions(), snapshot.internalAreaProtections(), snapshot.attempts(),
                snapshot.decision(), snapshot.pendingAnswer(), snapshot.terminal(), 100);
        restored.restoreSleepGateWait(snapshot.sleepGateWaiting());
        check(restored.sleepGateWaiting(), "a restored task keeps waiting instead of re-asking the gate");
        var plain = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        var plainEncoded = IntentStateCodec.encode("sleep-gate-test", List.of(), List.of(plain), Map.of(), List.of());
        check(!plainEncoded.getAsJsonArray("tasks").get(0).getAsJsonObject().has("sleep_gate_waiting"),
                "an unmarked task keeps its checkpoint free of the gate field");
    }

    private static IntentTaskRecord.DecisionSnapshot snapshotOf(IntentAction action) {
        return ((IntentAction.Decision) action).snapshot();
    }

    /** 白天且可用床的维度：白天门决策只在窗口确实关着时发射。 */
    private static void day(InteractionWorldTestHarness world) throws Exception {
        var time = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
        field(Level.class, "levelData").set(world.level, time);
        var dimension = new DimensionType(OptionalLong.empty(), true, false, false, true, 1.0, true, false,
                0, 16, 16, BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace("overworld"), 0,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
        field(Level.class, "dimensionTypeRegistration").set(world.level, Holder.direct(dimension));
        time.setDayTime(6000);
    }

    // ------------------------------------------------------------------

    private static IntentTaskRecord record;

    private static IntentTask task(InteractionWorldTestHarness world) throws Exception {
        var goal = new Goal("maicraft:sleep", "sleep", null, "{}", "{}", List.of(), List.of());
        record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        return new IntentTask(world.player, record, runtime());
    }

    private static IntentTaskRecord record(IntentTask task) { return record; }

    @SuppressWarnings("unchecked")
    private static List<IntentAction.Tool> chainOf(IntentTask task) throws Exception {
        return (List<IntentAction.Tool>) field(IntentTask.class, "chain").get(task);
    }

    private static void setChain(IntentTask task, List<IntentAction.Tool> chain) throws Exception {
        field(IntentTask.class, "chain").set(task, chain);
    }

    private static String resultMessage(IntentTask task) throws Exception {
        var result = (TaskResult) field(IntentTask.class, "terminalResult").get(task);
        return result == null ? "" : result.message();
    }

    private static TaskState failStep(IntentTask task, InteractionWorldTestHarness world, TaskResult failure)
            throws Exception {
        Method fail = IntentTask.class.getDeclaredMethod("failStep", TaskState.class, TaskResult.class);
        fail.setAccessible(true);
        return (TaskState) fail.invoke(task, TaskState.FAILED, failure);
    }

    /** ActorControlTestHarness 只在 client.actor 包内开放；这里用同等的反射字段读写。 */
    private static java.lang.reflect.Field field(Class<?> owner, String name) throws Exception {
        var f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static IntentRuntime runtime() throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void assertCarriedBedChain(IntentAction action, String expectedBlock) throws Exception {
        List<?> actions = ((IntentAction.Chain) action).actions();
        check(actions.size() == 3, "the fallback chain is build, goto then sleep");
        check("build".equals(((IntentAction.Tool) actions.get(0)).toolName())
                && "goto".equals(((IntentAction.Tool) actions.get(1)).toolName())
                && "sleep".equals(((IntentAction.Tool) actions.get(2)).toolName()),
                "the fallback chain order is build, goto, sleep");
        JsonObject build = JsonParser.parseString(
                ((IntentAction.Tool) actions.get(0)).argumentsJson()).getAsJsonObject();
        String buildArgs = ((IntentAction.Tool) actions.get(0)).argumentsJson();
        check(buildArgs.contains("\"op\":\"set\"") && buildArgs.contains("\"block_id\":\"" + expectedBlock + "\""),
                "the build step places the carried bed itself: " + buildArgs);
        // 走位与入睡都用显式坐标：村庄现成床与自带床常是同一种方块，按类型就近会走回被遮挡的现成床。
        JsonObject foot = build.getAsJsonArray("ops").get(0).getAsJsonObject();
        var head = new net.minecraft.core.BlockPos(foot.get("x").getAsInt(), foot.get("y").getAsInt(),
                foot.get("z").getAsInt())
                .relative(net.minecraft.core.Direction.byName(foot.get("facing").getAsString()));
        var gotoArgs = JsonParser.parseString(((IntentAction.Tool) actions.get(1)).argumentsJson()).getAsJsonObject();
        var sleepArgs = JsonParser.parseString(((IntentAction.Tool) actions.get(2)).argumentsJson()).getAsJsonObject();
        check(sleepArgs.get("x").getAsInt() == head.getX() && sleepArgs.get("y").getAsInt() == head.getY()
                        && sleepArgs.get("z").getAsInt() == head.getZ(),
                "the final sleep step targets the placed bed's head by explicit coordinates: " + sleepArgs);
        var stance = new net.minecraft.core.BlockPos(gotoArgs.get("x").getAsInt(), gotoArgs.get("y").getAsInt(),
                gotoArgs.get("z").getAsInt());
        var footPos = new net.minecraft.core.BlockPos(foot.get("x").getAsInt(), foot.get("y").getAsInt(),
                foot.get("z").getAsInt());
        boolean adjacent = stance.distManhattan(footPos) == 1 || stance.distManhattan(head) == 1;
        check(adjacent, "the goto step walks to a stance adjacent to the placed bed: " + gotoArgs);
    }

    /** 按 build 步的落位把床方块放进测试世界：换位重试的选址与 footOf 读的是真实床方块。 */
    private static void placeBedFromBuildStep(InteractionWorldTestHarness world, IntentAction.Tool build)
            throws Exception {
        JsonObject op = JsonParser.parseString(build.argumentsJson()).getAsJsonObject()
                .getAsJsonArray("ops").get(0).getAsJsonObject();
        var facing = net.minecraft.core.Direction.byName(op.get("facing").getAsString());
        var footPos = new net.minecraft.core.BlockPos(op.get("x").getAsInt(),
                op.get("y").getAsInt(), op.get("z").getAsInt());
        var footState = net.minecraft.world.level.block.Blocks.WHITE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, facing);
        var headState = net.minecraft.world.level.block.Blocks.WHITE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, facing);
        world.set(footPos, footState);
        world.set(footPos.relative(facing), headState);
    }

    /** 回执链 sleep 步的显式床头坐标；链上没有显式坐标时返回 null。 */
    private static net.minecraft.core.BlockPos sleepCoordsOf(IntentAction.Chain chain) {
        JsonObject sleepArgs = JsonParser.parseString(
                chain.actions().getLast().argumentsJson()).getAsJsonObject();
        if (!sleepArgs.has("x")) return null;
        return new net.minecraft.core.BlockPos(sleepArgs.get("x").getAsInt(),
                sleepArgs.get("y").getAsInt(), sleepArgs.get("z").getAsInt());
    }

    /** 可睡窗口内的夜晚与可用床的维度；回退方案只在确实能入睡时排链。 */
    private static void night(InteractionWorldTestHarness world) throws Exception {
        var time = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
        field(Level.class, "levelData").set(world.level, time);
        var dimension = new DimensionType(OptionalLong.empty(), true, false, false, true, 1.0, true, false,
                0, 16, 16, BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace("overworld"), 0,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
        field(Level.class, "dimensionTypeRegistration").set(world.level, Holder.direct(dimension));
        time.setDayTime(17 * 24_000L + 13_564);
        // 放床选址从玩家向外扫 5 圈；夹具只有 (0,0) 一个已加载区块，玩家贴边会读到未加载格。
        world.position(new net.minecraft.world.phys.Vec3(8.5, 1, 8.5));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
