// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.tools.interact.InteractAtTool;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskState;

/** Complete semantic entry through native task compilation, using the real loaded section index. */
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
        System.out.println("ExactInteractionTargetTest: passed");
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
        var result = new java.util.concurrent.atomic.AtomicReference<InteractAtTaskRecord>();
        TaskDispatch.captureNext(record -> result.set((InteractAtTaskRecord) record),
                () -> new InteractAtTool().onGameCall("exact-target", tool.arguments(), f.player,
                        ignored -> { throw new AssertionError("captured internal interaction replied directly"); }));
        return result.get();
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
