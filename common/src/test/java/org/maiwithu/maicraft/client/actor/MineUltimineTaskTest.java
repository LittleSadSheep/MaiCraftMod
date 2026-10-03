// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineBreak;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSession;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** 从采矿任务核对整脉破坏、集中掉落和最终库存；挖完矿脉但尚未入包时不能交付成功。 */
public final class MineUltimineTaskTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.enableCraftingTransactions(); h.h.minecraft.screen = null; h.position(new Vec3(8.5, 1, 8.5));
            ActorControlTestHarness.field(LivingEntity.class, "activeEffects").set(h.player, new HashMap<>());
            BlockPos origin = new BlockPos(8, 2, 10); List<BlockPos> selection = List.of(origin, origin.south());
            selection.forEach(at -> h.set(at, Blocks.DIRT.defaultBlockState()));
            var record = new MineBlockTaskRecord("native-vein", 1000, Set.of(Blocks.DIRT), 2, "dirt", Set.of(Items.DIRT))
                    .withinRadius(h.player.blockPosition(), 16);
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            var control = new UltimineBreakTest.NativeControl(selection);
            var action = new UltimineBreak(h.player, origin, null, UltimineSession.Mode.MINING, selection::contains, at -> false, control);
            ActorControlTestHarness.field(MineCompanionTask.class, "chainBreak").set(task, action);
            ActorControlTestHarness.field(MineCompanionTask.class, "activeTarget").set(task, origin);
            int[] swings = {0};
            h.mode.breaking = at -> { if (++swings[0] >= 3) selection.forEach(cell -> h.set(cell, Blocks.AIR.defaultBlockState())); };
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 180; tick++) {
                h.nextTick(); state = task.tick(h.player); DiscardFireTest.align(h);
                if (ActorControlTestHarness.field(MineCompanionTask.class, "chainBreak").get(task) == null) break;
            }
            check(state == TaskState.RUNNING && record.getMined() == 0, "native vein destruction alone does not satisfy inventory delivery");
            check(task.mustSettleBeforeSatisfiedCancellation(), "parent acquisition must wait for the native batch and its drops");
            var origins = (Map<?, ?>) ActorControlTestHarness.field(MineCompanionTask.class, "anticipatedDrops").get(task);
            check(origins.size() == 1 && origins.containsKey(origin), "FTB drops are collected at the seed rather than buried secondary positions");
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 2));
            for (int tick = 0; tick < 30 && state == TaskState.RUNNING; tick++) { h.nextTick(); state = task.tick(h.player); }
            var result = task.result(state).data();
            check(state == TaskState.SUCCESS && result.get("gathered").equals(2), "only actual carried items finish the mine task");
            check(((List<?>) result.get("confirmed_harvests")).size() == 2 && ((List<?>) result.get("ultimine_actions")).size() == 1,
                    "seed and secondary block effects survive the default task receipt");
        }
        System.out.println("MineUltimineTaskTest: native vein delivery passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
