// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.suicide.SuicideRequest;
import org.maiwithu.maicraft.core.task.suicide.SuicideTask;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskRecord;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskTest;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 洞里没有现成危险时用随身打火石原地点火；火由原生回执注入，熄灭后再点，被拒绝时不换格反复点。 */
public final class SuicideFireTest {
    private static final BlockPos CELL = new BlockPos(8, 1, 8);

    public static void main(String[] args) throws Exception {
        try (var world = cave()) {
            // auto：没有岩浆、高处和怪物，带着打火石就低头对脚下地面点火，站在火里受原生伤害。
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            world.mode.beforeBlockUse = () -> world.set(CELL, Blocks.FIRE.defaultBlockState());
            var task = task(world, "auto");
            run(world, task, () -> world.blockUses() == 1 && task.progress().get("ignitions_confirmed").equals(1));
            check(world.mode.usedHand == InteractionHand.MAIN_HAND && world.itemUses() == 0, "点火是对脚下方块的一次原生使用");
            check(task.progress().get("phase").equals("exposing_to_hazard") && task.progress().get("method").equals("fire"), "点着后应站在火里");
            world.player.setHealth(14);
            // 火自然熄灭后原地再点一次；火还烧着时不能重复右键浪费耐久。
            for (int tick = 0; tick < 20; tick++) step(world, task);
            check(world.blockUses() == 1, "火还烧着时不能重复点火");
            world.set(CELL, Blocks.AIR.defaultBlockState());
            run(world, task, () -> world.blockUses() == 2 && task.progress().get("ignitions_confirmed").equals(2));
            world.player.setHealth(0);
            check(task.observeDeath(world.player), "点火后本人死亡应完成寻死");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && result.data().get("death_observed").equals(true), "死亡成功应如实回报");
        }
        try (var world = cave()) {
            // 原生点击已发出却没出火（例如冒险模式拒绝）：只点这一次，不换邻格反复点，如实失败。
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            var task = task(world, "fire");
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 400 && state == TaskState.RUNNING; tick++) state = step(world, task);
            check(state == TaskState.FAILED && world.blockUses() == 1, "点火被原生拒绝后不能换格重复点火");
            check(task.result(state).message().contains("rejected"), "失败说明应指出点火被拒绝");
        }
        try (var world = cave()) {
            // 没带点火物且身边没有危险：如实失败并说明缺点火物，不做任何原生使用。
            var task = task(world, "fire");
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 40 && state == TaskState.RUNNING; tick++) state = step(world, task);
            check(state == TaskState.FAILED && world.blockUses() == 0, "没有点火物不能伪造点火");
            check(task.result(state).message().contains("No flint and steel"), "失败说明应指出缺少点火物");
            task.stop(world.player, Task.StopReason.REPLACED);
        }
        System.out.println("SuicideFireTest: passed");
    }

    private static InteractionWorldTestHarness cave() throws Exception {
        // 复用寻死夹具的模式与窗口，再撤掉岸边岩浆和石台，让角色站在空旷石地上，周围没有任何现成危险。
        var world = new InteractionWorldTestHarness();
        SuicideTaskTest.prepare(world);
        world.set(new BlockPos(9, 1, 8), Blocks.AIR.defaultBlockState());
        world.set(CELL, Blocks.AIR.defaultBlockState());
        world.position(Vec3.atBottomCenterOf(CELL)); world.player.setOnGround(true);
        world.h.minecraft.screen = null;
        return world;
    }

    private static SuicideTask task(InteractionWorldTestHarness world, String method) {
        return new SuicideTask(world.player, new SuicideTaskRecord("fire-test", new SuicideRequest(method, 8, 60, true)));
    }

    private static TaskState step(InteractionWorldTestHarness world, SuicideTask task) throws Exception {
        // 每刻先给出服务端方块确认，再推进任务，最后把身体控制器要求的视角落到角色上。
        world.nextTick(); world.level.acknowledgedSequence = world.level.blockSequence;
        TaskState state = task.tick(world.player);
        DiscardFireTest.align(world);
        return state;
    }

    private static void run(InteractionWorldTestHarness world, SuicideTask task, BooleanSupplier done) throws Exception {
        for (int tick = 0; tick < 200; tick++) {
            check(step(world, task) == TaskState.RUNNING, "活着时不能提前结束点火寻死: " + task.progress());
            if (done.getAsBoolean()) return;
        }
        throw new AssertionError("未完成原生点火: " + task.progress() + " uses=" + world.blockUses());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
