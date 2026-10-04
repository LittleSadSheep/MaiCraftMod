// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.tools.interact.InteractAtTool;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskState;

/** 从公开坐标目标贯穿原生任务，核对倒桶与嵌眼各自采用正确动作且只提交一次。 */
public final class TargetedItemUseTest {
    private static final BlockPos TARGET = new BlockPos(3, 1, 3);

    public static void main(String[] args) throws Exception {
        for (String ability : List.of(GeneralAbilityAdapter.USE_ITEM, GeneralAbilityAdapter.INTERACT)) {
            bucket(ability, Items.LAVA_BUCKET, Blocks.LAVA.defaultBlockState(), false);
            bucket(ability, Items.WATER_BUCKET, Blocks.WATER.defaultBlockState(), false);
        }
        bucket(GeneralAbilityAdapter.USE_ITEM, Items.LAVA_BUCKET, Blocks.OBSIDIAN.defaultBlockState(), true);
        eye();
        System.out.println("TargetedItemUseTest: exact bucket placement, returns, reactions and eye insertion passed");
    }

    private static Goal goal(String ability, String item) {
        return new Goal(ability, "在指定格原生使用物品", new Goal.SemanticTarget("coordinates", null,
                new Goal.WorldPosition(3, 1, 3, "minecraft:overworld"), null),
                "{\"item_id\":\"" + item + "\"}", "{}", List.of(), List.of());
    }

    private static InteractAtTaskRecord compile(Goal goal, InteractionWorldTestHarness world) {
        SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
        var action = AbilityAdapter.adapt(goal, world.player, null);
        check(action instanceof IntentAction.Tool, "公开坐标必须转交可接近的定点原生交互：" + action);
        var tool = (IntentAction.Tool) action;
        var record = new AtomicReference<InteractAtTaskRecord>();
        TaskDispatch.captureNext(value -> record.set((InteractAtTaskRecord) value), () ->
                new InteractAtTool().onGameCall("targeted-use", tool.arguments(), world.player, ignored -> {}));
        check(record.get().aim.equals(TARGET) && record.get().approachTarget && !record.get().heldItemUseOnly,
                "目标格、自动接近与定点语义都必须保留");
        return record.get();
    }

    private static void bucket(String ability, Item bucket, BlockState after, boolean changed) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(3.5, 1, .5));
            world.inventory.setItem(0, new ItemStack(bucket));
            var goal = goal(ability, bucket == Items.LAVA_BUCKET ? "minecraft:lava_bucket" : "minecraft:water_bucket");
            if (ability.equals(GeneralAbilityAdapter.USE_ITEM)) {
                JsonObject parameters = goal.parameters(); parameters.addProperty("expected_output_item_id", "minecraft:bucket");
                goal = goal.withParameters(parameters);
                // 定点请求不能把批次数量静默忽略，更不能按同一格连倒多桶。
                parameters.addProperty("count", 2);
                try { AbilityAdapter.adapt(goal.withParameters(parameters), world.player, null); throw new AssertionError("定点批次不应被接收"); }
                catch (IllegalArgumentException expected) { }
            }
            var record = compile(goal, world);
            check(record.requiredBlock == null, "未明确限定原方块时不锁死空气快照");
            // 模拟走近前空气被水流占据；仍允许真实倒桶，原生反应后的产物通过观察返回。
            if (changed) world.set(TARGET, Blocks.WATER.defaultBlockState());
            var task = new InteractAtCompanionTask(world.player, record); task.start(world.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 60 && state == TaskState.RUNNING; tick++) {
                var hit = FirstPersonInteractionTargeting.visibleBucketHit(world.level, world.player,
                        world.player.getEyePosition(), TARGET, world.player.blockInteractionRange(), bucket);
                if (hit != null) aim(world, hit.getLocation());
                world.nextTick(); state = task.tick(world.player);
                if (world.itemUses() == 1) {
                    world.set(TARGET, after); world.inventory.setItem(0, new ItemStack(Items.BUCKET));
                    world.level.acknowledgedSequence = world.level.blockSequence;
                }
            }
            var completed = task.result(state);
            check(state == TaskState.SUCCESS && world.itemUses() == 1 && world.blockUses() == 0,
                    "倒桶必须只执行一次原生物品射线，不能点击支撑方块：" + completed.message());
            var result = completed.data();
            check(result.get("target_observation") instanceof Map, "默认回执必须保留实际落格前后状态");
            if (record.expectedOutputItem != null)
                check(((Map<?, ?>) result.get("expected_output")).get("observed_increase").equals(1), "空桶返还必须入账");
        }
    }

    private static void eye() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(3.5, 1, .5));
            world.set(TARGET, Blocks.END_PORTAL_FRAME.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.ENDER_EYE));
            var task = new InteractAtCompanionTask(world.player, compile(goal(GeneralAbilityAdapter.USE_ITEM, "minecraft:ender_eye"), world));
            task.start(world.player); TaskState state = TaskState.RUNNING;
            // 对同一门框逐刻收敛镜头，服务端状态变化由夹具在一次方块提交后送回。
            for (int tick = 0; tick < 60 && state == TaskState.RUNNING; tick++) {
                aim(world, new Vec3(3.5, 1.4, 3.5)); world.nextTick(); state = task.tick(world.player);
                if (world.blockUses() == 1) {
                    world.set(TARGET, Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.HAS_EYE, true));
                    world.inventory.setItem(0, ItemStack.EMPTY); world.level.acknowledgedSequence = world.level.blockSequence;
                }
            }
            check(state == TaskState.SUCCESS && world.blockUses() == 1 && world.itemUses() == 0,
                    "定点嵌眼只使用门框，不能转成朝空气投眼");
            task.result(state);
        }
    }

    // 夹具显式完成原生渲染帧的转头，让断言关注真实命中与动作选择。
    private static void aim(InteractionWorldTestHarness world, Vec3 point) {
        Vec3 direction = point.subtract(world.player.getEyePosition());
        world.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        world.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
