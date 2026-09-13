// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 验证回收队列的有界调度；模拟已确认移除仅用于阶段交接，不宣称测试执行了原生破坏。 */
public final class BuildScaffoldCleanupScheduleTest {
    private static final BlockPos HIGH = new BlockPos(12, 6, 12), LOW = new BlockPos(4, 1, 4);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        currentSafeSupportPrecedesRemoteHeight();
        unreachableTargetWaitsForConfirmedChange();
        observedAirDoesNotAuthorizeAnotherPass();
        System.out.println("BuildScaffoldCleanupScheduleTest: passed");
    }

    private static void currentSafeSupportPrecedesRemoteHeight() throws Exception {
        try (var h = world()) {
            var task = task(h);
            check(call(task, "scaffoldSelectTick") == TaskState.RUNNING && field(task, "scaffold").get(task).equals(LOW)
                    && field(task, "phase").get(task).toString().equals("SCAFFOLD_BREAK"),
                    "当前位置已有安全可拆支撑时先处理它，不为最高远端支撑另走一趟");
            check(h.level.getBlockState(LOW).is(Blocks.DIRT) && h.blockUses() == 0 && h.itemUses() == 0,
                    "就地选取仍不提前破坏，下一步保留原生所有权与移除后落脚复核");
        }
    }

    private static void unreachableTargetWaitsForConfirmedChange() throws Exception {
        try (var h = world()) {
            var task = task(h); var record = record(task);
            exhaust(h, task, HIGH);
            check(call(task, "scaffoldNavTick") == TaskState.RUNNING && record.scaffoldLedger().contains(HIGH),
                    "单个目标站位耗尽只延后，不删除所有权或使整项立即失败");
            check(call(task, "scaffoldSelectTick") == TaskState.RUNNING && field(task, "scaffold").get(task).equals(LOW),
                    "延后高处目标后继续处理可达的低处支撑");
            // 注入一项已经得到 BROKE_TARGET 的世界结果，再调用同一个生产确认入口推进队列。
            h.set(LOW, Blocks.AIR.defaultBlockState()); call(task, "confirmedScaffoldBreak");
            check(call(task, "scaffoldSelectTick") == TaskState.RUNNING
                    && field(task, "scaffoldCleanupPasses").getInt(task) == 2
                    && field(task, "scaffoldQueue").get(task).equals(List.of(HIGH)), "确实拆掉一块后才重查之前无路的剩余目标");
            check(record.broken() == 1 && !record.scaffoldLedger().contains(LOW) && record.scaffoldLedger().contains(HIGH),
                    "只累计确认拆除，延后目标完整保留");
            exhaust(h, task, HIGH); call(task, "scaffoldNavTick");
            check(call(task, "scaffoldSelectTick") == TaskState.FAILED
                    && "scaffold_cleanup_no_progress".equals(field(task, "failureCode").get(task))
                    && field(task, "scaffoldCleanupPasses").getInt(task) == 2, "新一轮零变化后明确失败，不能开启第三轮盲目重试");
            var data = task.resultData();
            check(data.containsKey("scaffold_cleanup") && data.containsKey("last_deferred_scaffold")
                    && data.get("scaffold_cleanup_confirmed_removals").equals(1), "终态保留失败站位证据和准确拆除数量");
        }
    }

    private static void observedAirDoesNotAuthorizeAnotherPass() throws Exception {
        try (var h = world()) {
            var task = task(h); exhaust(h, task, HIGH); call(task, "scaffoldNavTick");
            h.set(LOW, Blocks.AIR.defaultBlockState());
            check(call(task, "scaffoldSelectTick") == TaskState.FAILED
                    && field(task, "scaffoldCleanupPasses").getInt(task) == 1 && record(task).broken() == 0,
                    "只观察到另一块为空气不能冒充自己的拆除进展来无限开启新一轮");
            check(record(task).scaffoldLedger().contains(HIGH) && h.blockUses() == 0 && h.itemUses() == 0,
                    "零进展失败不误拆高处目标，也不丢掉剩余支撑账");
        }
    }

    private static InteractionWorldTestHarness world() throws Exception {
        var h = new InteractionWorldTestHarness(); h.position(new Vec3(3.5, 1, 4.5));
        var info = new PlayerInfo(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "cleanup-schedule"), false);
        field(PlayerInfo.class, "gameMode").set(info, GameType.SURVIVAL);
        field(AbstractClientPlayer.class, "playerInfo").set(h.player, info);
        field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        h.set(HIGH, Blocks.DIRT.defaultBlockState()); h.set(LOW, Blocks.DIRT.defaultBlockState()); return h;
    }
    private static FirstPersonBuildCompanionTask task(InteractionWorldTestHarness h) throws Exception {
        var record = new BuildTaskRecord("cleanup-schedule", 1000, List.of(), false);
        record.scaffoldLedger().confirmed(HIGH, Blocks.DIRT.defaultBlockState()); record.scaffoldLedger().confirmed(LOW, Blocks.DIRT.defaultBlockState());
        var task = new FirstPersonBuildCompanionTask(h.player, record);
        field(task, "scaffoldQueue").set(task, List.of(HIGH, LOW)); field(task, "scaffoldCleanupPasses").setInt(task, 1); return task;
    }
    private static void exhaust(InteractionWorldTestHarness h, Object task, BlockPos target) throws Exception {
        var cleanup = new BuildScaffoldCleanup(h.player, target, LongSets.emptySet());
        field(cleanup, "cursor").setInt(cleanup, ((List<?>) field(cleanup, "cells").get(cleanup)).size());
        field(task, "scaffold").set(task, target); field(task, "scaffoldCleanup").set(task, cleanup);
    }
    private static BuildTaskRecord record(Object task) throws Exception { return (BuildTaskRecord) field(task, "r").get(task); }
    private static Object call(Object task, String name) throws Exception { var method = task.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task); }
    private static Field field(Object instance, String name) throws Exception {
        for (Class<?> type = instance instanceof Class<?> value ? value : instance.getClass(); type != null; type = type.getSuperclass()) try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
        } catch (NoSuchFieldException inherited) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
