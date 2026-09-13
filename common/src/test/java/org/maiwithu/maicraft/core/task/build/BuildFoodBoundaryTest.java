// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 推进真实施工入口，检查挖掘、选格和出坑先吃饭；未结束的挖掘或连锁状态仍保留原操作权。 */
public final class BuildFoodBoundaryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        excavationPipelineFeedsBeforeFirstBreak();
        selectionBoundariesFeed();
        pendingExcavationKeepsItsOperation();
        accessOnlyFeedsBeforeExitNavigation();
        System.out.println("BuildFoodBoundaryTest: passed");
    }
    private static void excavationPipelineFeedsBeforeFirstBreak() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos dirt = new BlockPos(5, 1, 5); h.set(dirt, Blocks.DIRT.defaultBlockState());
            var record = new BuildTaskRecord("dig-then-food", 1000, List.of(new BuildTaskRecord.Target(
                    Blocks.AIR, Items.AIR, dirt, "excavation", null, null, null)), true, true);
            var calls = new AtomicInteger(); var task = prepared(h, record, calls);
            task.start(h.player);
            for (int i = 0; i < 4 && calls.get() == 0; i++) { h.nextTick(); check(task.tick(h.player) == TaskState.RUNNING, "preflight and food preparation stay active"); }
            check(calls.get() == 1 && field(task, "phase").get(task).toString().equals("EXCAVATE")
                    && h.level.getBlockState(dirt).is(Blocks.DIRT) && h.blockUses() == 0,
                    "same-pipeline preflight transition checks hunger before starting its first native break");
            task.result(TaskState.CANCELLED);
        }
    }
    private static void selectionBoundariesFeed() throws Exception {
        for (String phase : List.of("SELECT", "SCAFFOLD_SELECT")) try (var h = new InteractionWorldTestHarness()) {
            var calls = new AtomicInteger(); var task = prepared(h, plan(), calls); phase(task, phase);
            check(task.tick(h.player) == TaskState.RUNNING && calls.get() == 1
                    && field(task, "phase").get(task).toString().equals(phase), "an eligible selection boundary eats before choosing another work cell");
            task.stop(h.player, Task.StopReason.PREEMPTED);
            check(!((BuildFoodPreparation) field(task, "foodPreparation").get(task)).active(), "pausing the build releases its own meal preparation");
            task.result(TaskState.CANCELLED);
        }
    }
    private static void pendingExcavationKeepsItsOperation() throws Exception {
        for (boolean chain : List.of(false, true)) try (var h = new InteractionWorldTestHarness()) {
            var calls = new AtomicInteger(); var task = prepared(h, plan(), calls); phase(task, "SELECT");
            // 用尚未结束的工具/连锁状态模拟事务屏障；进食不能先清掉屏障来抢占角色。
            if (chain) field(task, "ultimineArmed").setBoolean(task, true);
            else { Object digger = field(task, "digger").get(task); field(digger, "pos").set(digger, new BlockPos(5, 1, 5)); }
            task.tick(h.player);
            check(calls.get() == 0 && !((BuildFoodPreparation) field(task, "foodPreparation").get(task)).active(),
                    "unfinished excavation or chain preparation cannot be replaced with an eating child");
            task.result(TaskState.CANCELLED);
        }
    }
    private static void accessOnlyFeedsBeforeExitNavigation() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var record = new BuildTaskRecord("access-food", 1000, BuildSupplyAccessTest.preparePit(h), false, true);
            record.supplyAccessOnly(true); var calls = new AtomicInteger(); var task = prepared(h, record, calls);
            h.player.setHealth(1); h.player.getFoodData().setFoodLevel(0); task.start(h.player);
            for (int i = 0; i < 5 && calls.get() == 0; i++) { h.nextTick(); check(task.tick(h.player) == TaskState.RUNNING, "access-only preflight reaches food upkeep"); }
            check(calls.get() == 1 && field(task, "phase").get(task).toString().equals("EXCAVATE_EXIT")
                    && field(task, "nav").get(task) == null && h.inventory.getItem(0).getCount() == 64,
                    "a hungry critical body begins its own meal before any exit navigation, even when this child only prepares supply access");
            task.result(TaskState.CANCELLED);
        }
    }
    private static FirstPersonBuildCompanionTask prepared(InteractionWorldTestHarness h, BuildTaskRecord record, AtomicInteger calls) throws Exception {
        h.player.getFoodData().setFoodLevel(14); h.inventory.setItem(0, new ItemStack(Items.BREAD, 64)); record.previewManaged(true);
        var task = new FirstPersonBuildCompanionTask(h.player, record);
        field(task, "foodPreparation").set(task, new BuildFoodPreparation((player, food) -> {
            calls.incrementAndGet(); return new Task() {
                public TaskState tick(LocalPlayer p) { return TaskState.RUNNING; }
                public void stop(LocalPlayer p, StopReason why) {}
                public TaskResult result(TaskState terminal) { return TaskResult.fail("fixture food interrupted"); }
                public String name() { return "bounded fixture meal"; }
            };
        }));
        return task;
    }
    private static BuildTaskRecord plan() { return new BuildTaskRecord("selection-food", 1000, List.of(new BuildTaskRecord.Target(
            Blocks.AIR, Items.AIR, new BlockPos(5, 1, 5), "air", null, null, null)), false, true); }
    private static void phase(Object task, String name) throws Exception {
        Field field = field(task, "phase"); for (Object value : field.getType().getEnumConstants()) if (value.toString().equals(name)) field.set(task, value);
    }
    private static Field field(Object instance, String name) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
        } catch (NoSuchFieldException inherited) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
