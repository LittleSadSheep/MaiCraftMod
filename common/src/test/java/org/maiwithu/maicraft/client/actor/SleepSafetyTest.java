// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Difficulty;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.sleep.SleepCompanionTask;
import org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord;
import org.maiwithu.maicraft.core.tools.SleepOps;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.task.TaskState;

/** 直接推进睡觉任务与规划入口，确认危险维度在任何床点击之前被拒绝。 */
public final class SleepSafetyTest {
    private static final BlockPos BED = new BlockPos(1, 1, 1);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        refusesUnsafePlanning();
        refusesUnsafeClick(false);
        refusesUnsafeClick(true);
        safeBedStillWorks();
        bedAreaTargetTravelsThenSleeps();
        System.out.println("SleepSafetyTest: unsafe dimensions never submit a bed click");
    }

    private static void refusesUnsafePlanning() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            bedWorks(f, false);
            f.set(BED, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD));
            var plan = plan(f);
            check(!plan.executable() && plan.refusal().contains("explode"),
                    "a loaded, reachable bed must be refused when bedWorks is false");
            var automatic = new SleepOps().plan(null, null, null, f.player, new ToolContext("sleep", 0));
            check(!automatic.executable() && automatic.refusal().contains("explode"),
                    "automatic bed selection must explain the same dimension hazard");

            var adapter = Class.forName("org.maiwithu.maicraft.intent.AbilityAdapter")
                    .getDeclaredMethod("sleep", Goal.class, LocalPlayer.class,
                            org.maiwithu.maicraft.intent.IntentRuntime.class);
            adapter.setAccessible(true);
            var goal = new Goal("maicraft:sleep", "sleep safely", null, "{}", "{}", List.of(), List.of());
            Object action = adapter.invoke(null, goal, f.player, null);
            check(action.getClass().getSimpleName().equals("Decision") && action.toString().contains("explode"),
                    "semantic sleep must explain the hazard before suggesting a bed or waiting for night");
            check(f.level.searches == 0 && f.blockUses() == 0,
                    "an unsafe sleep plan must not start terrain searches or clicks");
        }
    }

    private static void refusesUnsafeClick(boolean afterAiming) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            bedWorks(f, afterAiming);
            f.set(BED, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD));
            var task = new SleepCompanionTask(f.player, new SleepTaskRecord("sleep", 200, BED));
            task.start(f.player);
            if (afterAiming) {
                check(task.tick(f.player) == TaskState.RUNNING, "a safe bed may begin aiming");
                f.nextTick();
                aim(f);
                bedWorks(f, false);
            }
            TaskState state = task.tick(f.player);
            var result = task.result(state);
            check(state == TaskState.FAILED && "hazard".equals(result.data().get("failure_type")),
                    "direct execution must classify an unsafe bed as a hazard, including after aiming");
            check(result.message().contains("explode") && f.blockUses() == 0,
                    "waiting for sleep confirmation cannot undo an explosive click");
        }
    }

    private static void safeBedStillWorks() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            bedWorks(f, true);
            // 维度能力决定能否用床；不能把原版维度名称当成 Mod 维度的固定规则。
            ActorControlTestHarness.field(Level.class, "dimension").set(f.level, Level.NETHER);
            f.set(BED, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD));
            var plan = plan(f);
            check(plan.executable(), "a dimension that supports beds must keep the normal plan");
            var task = new SleepCompanionTask(f.player, plan.task());
            task.start(f.player);
            check(task.tick(f.player) == TaskState.RUNNING, "the first tick only begins aiming");
            f.nextTick();
            aim(f);
            check(task.tick(f.player) == TaskState.RUNNING && f.blockUses() == 1,
                    "a safe, aligned bed still submits exactly one native click");
            ActorControlTestHarness.field(f.player.getClass(), "sleeping").setBoolean(f.player, true);
            f.nextTick();
            check(task.tick(f.player) == TaskState.SUCCESS && f.blockUses() == 1,
                    "observed sleep completes without another click");
            task.result(TaskState.SUCCESS);
        }
    }

    /** coordinates 床区目标按准确坐标先走后睡，床的位置进入请求而不再只认 current_place。 */
    private static void bedAreaTargetTravelsThenSleeps() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            bedWorks(f, true);
            f.set(BED, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD));
            var time = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            ActorControlTestHarness.field(Level.class, "levelData").set(f.level, time);
            time.setDayTime(17 * 24_000L + 13_564);
            var goal = new Goal("maicraft:sleep", "sleep at the camp",
                    new Goal.SemanticTarget("coordinates", null,
                            new Goal.WorldPosition(BED.getX(), BED.getY(), BED.getZ(), "minecraft:overworld"), null),
                    "{}", "{}", List.of(), List.of());
            var adapter = Class.forName("org.maiwithu.maicraft.intent.AbilityAdapter")
                    .getDeclaredMethod("sleep", Goal.class, LocalPlayer.class,
                            org.maiwithu.maicraft.intent.IntentRuntime.class);
            adapter.setAccessible(true);
            Object action = adapter.invoke(null, goal, f.player, null);
            check(action.getClass().getSimpleName().equals("Chain"),
                    "a coordinates bed-area target resolves into a goto-then-sleep chain, got " + action);
            @SuppressWarnings("unchecked")
            List<Object> actions = (List<Object>) action.getClass().getMethod("actions").invoke(action);
            check(actions.size() == 2, "the chain is exactly goto then sleep");
            String gotoArgs = (String) actions.get(0).getClass().getMethod("argumentsJson").invoke(actions.get(0));
            String tool0 = (String) actions.get(0).getClass().getMethod("toolName").invoke(actions.get(0));
            String tool1 = (String) actions.get(1).getClass().getMethod("toolName").invoke(actions.get(1));
            check("goto".equals(tool0) && "sleep".equals(tool1), "chain order is goto then sleep");
            check(gotoArgs.contains("\"x\":" + BED.getX()) && gotoArgs.contains("\"y\":" + BED.getY())
                            && gotoArgs.contains("\"z\":" + BED.getZ()),
                    "the goto step travels to the bed coordinates themselves: " + gotoArgs);
        }
    }

    private static SleepOps.Plan plan(InteractionWorldTestHarness f) {
        return new SleepOps().plan(BED.getX(), BED.getY(), BED.getZ(), f.player, new ToolContext("sleep", 0));
    }

    private static void aim(InteractionWorldTestHarness f) {
        Vec3 direction = Vec3.atCenterOf(BED).subtract(f.player.getEyePosition());
        f.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        f.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance())));
    }

    static void bedWorks(InteractionWorldTestHarness f, boolean works) throws Exception {
        var dimension = new DimensionType(OptionalLong.empty(), true, false, false, true,
                1.0, works, false, 0, 16, 16, BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace("overworld"), 0,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
        ActorControlTestHarness.field(Level.class, "dimensionTypeRegistration").set(f.level, Holder.direct(dimension));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
