// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
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
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 166：携带床时 sleep 先凑现成床，现成床被遮挡、够不着或失效不再终局失败，
 * 而是回退就近放下自带床并入睡；回执如实记录用了自带床，无自带床维持诚实失败。
 */
public final class SleepBedFallbackRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        occludedVillageBedFallsBackToCarriedBed();
        explicitBedTargetNeverSwapsBeds();
        noCarriedBedKeepsHonestFailure();
        emptyWorldPlansCarriedBedDirectly();
        fallbackChainFailureReportsBothAttempts();
        fallbackSuccessReceiptNamesTheCarriedBed();
        System.out.println("SleepBedFallbackRegression: passed");
    }

    /** ①现成床被遮挡：回退链是 放自带床 → 走过去 → 入睡，放置用的是背包里的那张床。 */
    private static void occludedVillageBedFallsBackToCarriedBed() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            night(world);
            world.inventory.add(new ItemStack(Items.WHITE_BED));
            var goal = new Goal("maicraft:sleep", "sleep through the night", null, "{}", "{}", List.of(), List.of());
            IntentAction action = AbilityAdapter.carriedBedFallback(goal, world.player, "occluded");
            check(action instanceof IntentAction.Chain, "an occluded village bed with a carried bed engages the fallback chain");
            assertCarriedBedChain((IntentAction.Chain) action, "minecraft:white_bed");
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

    /** 回退链再次失败：终局话术把两段失败都带全，不回退第二次。 */
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
            TaskState state = failStep(task, world,
                    TaskResult.fail("sleep interaction was rejected", Map.of("failure_type", "unknown")));
            check(state == TaskState.FAILED, "a second failure inside the fallback chain ends the task");
            check(resultMessage(task).contains("carried-bed fallback also failed")
                            && resultMessage(task).contains("occluded from the current stance"),
                    "the terminal message carries both the fallback failure and the earlier village-bed failure");
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
        String buildArgs = ((IntentAction.Tool) actions.get(0)).argumentsJson();
        check(buildArgs.contains("\"op\":\"set\"") && buildArgs.contains("\"block_id\":\"" + expectedBlock + "\""),
                "the build step places the carried bed itself: " + buildArgs);
        check(((IntentAction.Tool) actions.get(2)).argumentsJson().contains("{}"),
                "the final sleep step lets the placed bed be found on arrival");
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
