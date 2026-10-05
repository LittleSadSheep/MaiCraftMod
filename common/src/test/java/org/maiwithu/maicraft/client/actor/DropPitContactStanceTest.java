// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.collect.CollectItemsApproach;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 一格深凹格的掉落物停在站立面下一格；坑边站位属普通行走，候选不塌缩成掉落格自身。 */
public final class DropPitContactStanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        pitRimStanceAcceptedWithoutTerrainPermit();
        rimToPitDescentFormsExecutableApproach();
        pitBottomContactStillNudgesInPlace();
        deeperPitStillNeedsThePitCell();
        deeperPitDescentStillRejected();
        System.out.println("DropPitContactStanceTest: pit-rim contact stances and task entry passed");
    }

    private static void pitRimStanceAcceptedWithoutTerrainPermit() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 抬高一层的平台挖掉一格形成 1 格深凹格；金锭停在坑底，玩家站在坑边。
            for (int x = 5; x <= 10; x++) for (int z = 6; z <= 10; z++)
                world.set(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            BlockPos pit = new BlockPos(8, 1, 8), stance = new BlockPos(7, 2, 8);
            world.set(pit, Blocks.AIR.defaultBlockState());
            world.position(new Vec3(6.5, 2, 8.5));
            var drop = ItemEntityReceiptsTest.item(world, 571, new Vec3(8.5, 1, 8.5), new ItemStack(Items.RAW_IRON));
            check(CollectItemsApproach.goal(world.player, List.of(drop)).goal().isAt(stance),
                    "坑边站位无须开路授权即成为接近候选，候选集合不再只剩掉落格");
            check(CollectItemsApproach.goal(world.player, List.of(drop)).goal().isAt(pit),
                    "掉落格仍保留为候选，由正常导航判断是否走下凹格");
            var record = new CollectItemsTaskRecord("pit-rim-pickup", 1000, Set.of(Items.RAW_IRON), 16,
                    "raw iron", Set.of(drop.getUUID()));
            var task = new CollectItemsCompanionTask(world.player, record); task.start(world.player);
            check(task.tick(world.player) == TaskState.RUNNING, "同一 collect_items 任务开始接近");
            var goal = (GoalCompiler.Compiled) call(task, "targetGoal");
            check(goal != null && goal.goal().isAt(stance), "drop_ref 链路的实时目标包含坑边站位");
        }
    }

    /** 坑边站位到位后，最后一段接近目标应是坑底落脚点，踏面安全闸放行走下 1 格凹格。 */
    private static void rimToPitDescentFormsExecutableApproach() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            for (int x = 5; x <= 10; x++) for (int z = 6; z <= 10; z++)
                world.set(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(8, 1, 8), Blocks.AIR.defaultBlockState());
            world.position(new Vec3(7.5, 2, 8.5));
            var drop = ItemEntityReceiptsTest.item(world, 573, new Vec3(8.5, 1, 8.5), new ItemStack(Items.RAW_IRON));
            Vec3 point = CollectItemsApproach.nudgePoint(world.player, drop);
            check(point.y == 1 && point.x == 8.5 && point.z == 8.5,
                    "坑边站位接触不到下层掉落物时，接近目标构成坑底落脚点而非悬空的掉落坐标");
            check(CollectItemsApproach.safeNudge(world.player, point),
                    "踏面安全闸放行到站立面下一格可站立凹格的普通下坑");
        }
    }

    /** 走下凹格后的坑底段沿用原语义：同格接触点就地微调，不额外移动。 */
    private static void pitBottomContactStillNudgesInPlace() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            for (int x = 5; x <= 10; x++) for (int z = 6; z <= 10; z++)
                world.set(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(8, 1, 8), Blocks.AIR.defaultBlockState());
            world.position(new Vec3(8.5, 1, 8.5));
            var drop = ItemEntityReceiptsTest.item(world, 574, new Vec3(8.5, 1.1, 8.5), new ItemStack(Items.RAW_IRON));
            Vec3 point = CollectItemsApproach.nudgePoint(world.player, drop);
            check(point.x == 8.5 && point.y == 1 && point.z == 8.5,
                    "坑底站位经原版接触核查取得同格接触点");
            check(CollectItemsApproach.safeNudge(world.player, point), "坑底同格短走保持放行");
        }
    }

    private static void deeperPitStillNeedsThePitCell() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 两格深的坑底掉落物超出普通行走范围：坑边站位不得凭放宽后的接触范围混进候选。
            for (int x = 5; x <= 10; x++) for (int z = 6; z <= 10; z++) {
                world.set(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
                world.set(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
            }
            BlockPos pitTop = new BlockPos(8, 2, 8), pitBottom = new BlockPos(8, 1, 8), stance = new BlockPos(7, 3, 8);
            world.set(pitTop, Blocks.AIR.defaultBlockState());
            world.set(pitBottom, Blocks.AIR.defaultBlockState());
            world.position(new Vec3(6.5, 3, 8.5));
            var drop = ItemEntityReceiptsTest.item(world, 572, new Vec3(8.5, 1, 8.5), new ItemStack(Items.RAW_IRON));
            check(!CollectItemsApproach.goal(world.player, List.of(drop)).goal().isAt(stance),
                    "低于站立面两格的掉落物不把坑边格当作接触站位");
        }
    }

    /** 两格深坑的最后一段仍被踏面安全闸拒绝：层差超出普通行走范围时接近目标不构成可执行下坑。 */
    private static void deeperPitDescentStillRejected() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            for (int x = 5; x <= 10; x++) for (int z = 6; z <= 10; z++) {
                world.set(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
                world.set(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
            }
            world.set(new BlockPos(8, 2, 8), Blocks.AIR.defaultBlockState());
            world.set(new BlockPos(8, 1, 8), Blocks.AIR.defaultBlockState());
            world.position(new Vec3(7.5, 3, 8.5));
            var drop = ItemEntityReceiptsTest.item(world, 575, new Vec3(8.5, 1, 8.5), new ItemStack(Items.RAW_IRON));
            check(CollectItemsApproach.nudgePoint(world.player, drop).y == 1
                            && CollectItemsApproach.nudgePoint(world.player, drop).x == 8.5,
                    "两格深坑无下坑落脚点，接近目标回落为坑底掉落坐标本身");
            check(!CollectItemsApproach.safeNudge(world.player, drop.position()),
                    "踏面安全闸拒绝走下两格深的坑");
        }
    }

    private static Object call(Object owner, String name, Object... args) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                method.setAccessible(true);
                return method.invoke(owner, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
