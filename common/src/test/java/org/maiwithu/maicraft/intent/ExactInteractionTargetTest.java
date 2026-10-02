// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.interact.UseItemBatchTaskRecord;
import org.maiwithu.maicraft.core.tools.interact.InteractAtTool;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskState;
import java.util.concurrent.atomic.AtomicReference;

/** 通过原生任务编译器完成完整语义入口，并使用真实已加载区块索引。 */
public final class ExactInteractionTargetTest {
    private static final BlockPos TARGET = new BlockPos(3, 2, 3), NEIGHBOR = new BlockPos(5, 2, 3);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var f = new InteractionWorldTestHarness()) {
            f.set(TARGET, Blocks.SPRUCE_BUTTON.defaultBlockState());
            f.set(NEIGHBOR, Blocks.SPRUCE_BUTTON.defaultBlockState());
            for (String blockId : new String[]{null, "minecraft:spruce_button"}) {
                var nativeTask = compile(adapt(goal("coordinates", NEIGHBOR, blockId, null), f), f);
                check(nativeTask.aim.equals(NEIGHBOR), "coordinates must keep the exact button even with a nearer match");
                check(nativeTask.requiredBlock == Blocks.SPRUCE_BUTTON && nativeTask.expectedBlock == null,
                        "the observed input block must survive internal tool parsing as a precondition, not an outcome");
                check(nativeTask.emptyHand, "omitting an item prepares an empty hand through the complete semantic and internal tool path");
            }
            check(f.level.searches == 0, "exact targets must bypass the section index entirely");
            f.set(NEIGHBOR, Blocks.STONE.defaultBlockState());
            var mismatch = adapt(goal("coordinates", NEIGHBOR, "minecraft:spruce_button", "nearest"), f);
            check(mismatch instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("does not match"),
                    "a type mismatch must refuse, even when nearest could find a replacement button");
            f.set(NEIGHBOR, Blocks.AIR.defaultBlockState());
            check(adapt(goal("coordinates", NEIGHBOR, null, null), f) instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("empty"), "an observed block removed before adaptation must fail");
            int reads = f.level.blockReads;
            check(adapt(goal("coordinates", new BlockPos(32, 2, 3), null, null), f) instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("not loaded"), "unloaded coordinates must be reported explicitly");
            check(f.level.blockReads == reads && f.level.searches == 0,
                    "unloaded exact targets must neither read the cell nor load/search neighboring chunks");
            Goal otherDimension = goal("coordinates", TARGET, null, null).withTarget(new Goal.SemanticTarget(
                    "coordinates", null, new Goal.WorldPosition(3, 2, 3, "minecraft:the_nether"), null));
            check(adapt(otherDimension, f) instanceof IntentAction.Decision && f.level.blockReads == reads,
                    "coordinates in a different dimension must not read a same-numbered local cell");
            f.set(NEIGHBOR, Blocks.SPRUCE_BUTTON.defaultBlockState());
            var unique = adapt(goal(null, null, "minecraft:spruce_button", null), f);
            check(unique instanceof IntentAction.Decision decision && decision.snapshot().question().contains("Several"),
                    "ordinary search must still report ambiguous buttons");
            var nearest = compile(adapt(goal("nearest", null, "minecraft:spruce_button", null), f), f);
            check(nearest.aim.equals(TARGET) && f.level.searches > 0,
                    "nearest search must retain actual loaded-index selection");
        }
        changedAfterCompilation(false);
        changedAfterCompilation(true);
        heldItemUseHasItsOwnSemanticEntry();
        foodEffectsAreAnExplicitPlannerChoice();
        bucketSourceSelection(false);
        bucketSourceSelection(true);
        System.out.println("ExactInteractionTargetTest: passed");
    }

    /** 洪水超过最近候选窗口和索引饱和阈值时，仍选真源格；同方块水位变化必须立即更新候选。 */
    private static void bucketSourceSelection(boolean dense) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.inventory.setItem(0, new ItemStack(Items.BUCKET));
            var flow = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4);
            for (int x = 1; x <= (dense ? 12 : 4); x++) for (int z = 1; z <= 14; z++)
                for (int y = 1; y <= 2; y++) f.set(new BlockPos(x, y, z), flow);
            var source = new BlockPos(14, 2, 14); f.set(source, Blocks.WATER.defaultBlockState());
            var parameters = new JsonObject(); parameters.addProperty("block_id", "minecraft:water");
            parameters.addProperty("item_id", "minecraft:bucket"); parameters.addProperty("selection", "nearest");
            var goal = new Goal(GeneralAbilityAdapter.INTERACT, "fill bucket", null,
                    parameters.toString(), "{}", List.of(), List.of());
            check(compile(adapt(goal, f), f).aim.equals(source), "flowing water cannot crowd the real source out of the nearest window");
            f.set(source, flow);
            var absent = adapt(goal, f);
            check(absent instanceof IntentAction.Report report && !report.result().success()
                    && report.result().data().get("failure_code").equals("no_source_block")
                    && Boolean.FALSE.equals(report.result().data().get("native_action_submitted")),
                    "a flood with no source returns a typed, unsubmitted outcome without clicking or waiting for confirmation");
            var renewed = new BlockPos(1, 2, 3); f.set(renewed, Blocks.WATER.defaultBlockState());
            check(compile(adapt(goal, f), f).aim.equals(renewed), "flow-to-source changes invalidate the same-block cached query");
            // 精确指定的流水不换成旁边水源；原生任务在创建导航或提交点击前说明无法收取。
            var exact = goal.withTarget(new Goal.SemanticTarget("coordinates", null,
                    new Goal.WorldPosition(source.getX(), source.getY(), source.getZ(), "minecraft:overworld"), null));
            var task = new InteractAtCompanionTask(f.player, compile(adapt(exact, f), f));
            task.start(f.player); check(task.tick(f.player) == TaskState.FAILED, "exact flowing target fails before travel");
            var result = task.result(TaskState.FAILED);
            check("not_a_source_block".equals(result.data().get("failure_type")) && f.itemUses() == 0 && f.blockUses() == 0,
                    "native failure preserves the actual source condition and sends no use");
            // 满桶倒入流水仍是可尝试的原生动作，不能套用空桶选源门槛。
            f.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET)); parameters.addProperty("item_id", "minecraft:water_bucket");
            check(adapt(goal.withParameters(parameters), f) instanceof IntentAction.Tool, "filled bucket interaction keeps flowing-water targets");
        }
    }

    /** 默认补食仍避开效果食物；自主任务点名并接受效果后可直接执行，不插入额外人工批准。 */
    private static void foodEffectsAreAnExplicitPlannerChoice() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(6); h.inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
            var p = new JsonObject(); p.addProperty("item_id", "minecraft:rotten_flesh");
            var goal = new Goal(GeneralAbilityAdapter.CONSUME, "restore hunger", new Goal.SemanticTarget("current_place", null, null, null), p.toString(), "{}", List.of(), List.of());
            check(AbilityAdapter.adapt(goal, h.player, null) instanceof IntentAction.Decision, "default food selection does not silently accept effects");
            p.addProperty("allow_effects", true); SemanticGoalContract.validate(goal.withParameters(p), GeneralAbilityAdapter.abilities());
            check(AbilityAdapter.adapt(goal.withParameters(p), h.player, null) instanceof IntentAction.Tool && h.itemUses() == 0,
                    "explicit planner choice compiles one native eating action without requesting human approval or eating during planning");
            check(SemanticAbilityCatalog.describe(GeneralAbilityAdapter.CONSUME).toString().contains("planner explicitly accepts"), "the public contract identifies the actual decision maker");
        }
    }

    private static void heldItemUseHasItsOwnSemanticEntry() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 有副手加工机制的物品无需虚构实体或方块目标，公开接口也不接收鼠标键和持用刻数。
            h.inventory.setItem(0, new ItemStack(Items.HONEY_BOTTLE));
            var p = new JsonObject(); p.addProperty("item_id", "minecraft:honey_bottle"); p.addProperty("expected_output_item_id", "minecraft:glass_bottle");
            var goal = new Goal(GeneralAbilityAdapter.USE_ITEM, "use carried item", new Goal.SemanticTarget("current_place", null, null, null), p.toString(), "{}", List.of(), List.of());
            SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
            var action = (IntentAction.Native) AbilityAdapter.adapt(goal, h.player, null);
            var r = (InteractAtTaskRecord) action.record();
            check(r.heldItemUseOnly && r.aim == null && r.expectedOutputItem == Items.GLASS_BOTTLE && h.itemUses() == 0,
                    "semantic adaptation prepares native item use without clicking");
            // 一次语义请求携带产量和原料，编译器交给 Mod 批次执行，不展开成多条模型指令。
            p.addProperty("count", 15); p.addProperty("ingredient_item_id", "minecraft:quartz");
            SemanticGoalContract.validate(goal.withParameters(p), GeneralAbilityAdapter.abilities());
            var batch = (UseItemBatchTaskRecord) ((IntentAction.Native) AbilityAdapter.adapt(goal.withParameters(p), h.player, null)).record();
            check(batch.count == 15 && batch.ingredient == Items.QUARTZ && batch.output == Items.GLASS_BOTTLE && h.itemUses() == 0, "batch adaptation preserves semantic material and count without acting");
            for (String invalid : new String[]{"0", "65", "1.5", "null", "\"3\""}) {
                p.add("count", JsonParser.parseString(invalid));
                try { AbilityAdapter.adapt(goal.withParameters(p), h.player, null); throw new AssertionError("invalid batch count accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            p.addProperty("count", 15);
            p.addProperty("hold_ticks", 32);
            try { SemanticGoalContract.validate(goal.withParameters(p), GeneralAbilityAdapter.abilities()); throw new AssertionError("raw hold ticks accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static Goal goal(String kind, BlockPos at, String blockId, String selection) {
        JsonObject parameters = new JsonObject();
        if (blockId != null) parameters.addProperty("block_id", blockId);
        if (selection != null) parameters.addProperty("selection", selection);
        var target = kind == null ? null : new Goal.SemanticTarget(kind, null, at == null ? null
                : new Goal.WorldPosition(at.getX(), at.getY(), at.getZ(), "minecraft:overworld"), null);
        return new Goal(GeneralAbilityAdapter.INTERACT, "use this button", target, parameters.toString(), "{}", List.of(), List.of());
    }

    private static IntentAction adapt(Goal goal, InteractionWorldTestHarness f) throws Exception {
        SemanticGoalContract.validate(goal, Set.of(GeneralAbilityAdapter.INTERACT));
        for (int tick = 0; tick < 100; tick++) {
            IntentAction action = AbilityAdapter.adapt(goal, f.player, null);
            if (action != IntentAction.Pending.INSTANCE) return action;
            f.nextTick();
        }
        throw new AssertionError("the real loaded section query never completed");
    }

    private static void changedAfterCompilation(boolean afterAim) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.set(TARGET, Blocks.SPRUCE_BUTTON.defaultBlockState());
            var record = compile(adapt(goal("coordinates", TARGET, null, null), f), f);
            var task = new InteractAtCompanionTask(f.player, record);
            var act = InteractAtCompanionTask.class.getDeclaredMethod("act"); act.setAccessible(true);
            if (afterAim) check(act.invoke(task) == TaskState.RUNNING, "first look waits for the camera before pressing");
            f.set(TARGET, Blocks.STONE.defaultBlockState());
            f.nextTick();
            check(act.invoke(task) == TaskState.FAILED && f.itemUses() == 0 && f.blockUses() == 0,
                    "a different non-air block at the compiled coordinate must fail before any native use");
        }
    }

    private static InteractAtTaskRecord compile(IntentAction action, InteractionWorldTestHarness f) {
        IntentAction.Tool tool = action instanceof IntentAction.Tool direct ? direct
                : action instanceof IntentAction.Chain chain ? chain.actions().getLast() : null;
        check(tool != null && tool.toolName().equals("interact_at"), "the exact target must compile to native interaction: " + action);
        var result = new AtomicReference<InteractAtTaskRecord>();
        TaskDispatch.captureNext(record -> result.set((InteractAtTaskRecord) record),
                () -> new InteractAtTool().onGameCall("exact-target", tool.arguments(), f.player,
                        ignored -> { throw new AssertionError("captured internal interaction replied directly"); }));
        return result.get();
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
