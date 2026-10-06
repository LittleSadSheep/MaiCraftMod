// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 收尾清理包裹回归：失败、绕过验证的直达成功与操作者取消都不能把台账里的自有临时支撑留在世界里。
 * 181 实机病症的锚点——回执如实列 remaining_scaffolds 却零 removal，失败收尾从不进入清理阶段；
 * 184 补齐取消终态——取消先转入仅清理缓期，清完账才交回 CANCELLED。
 */
public final class BuildTerminalScaffoldCleanupTest {
    private static final BlockPos STANDING = new BlockPos(6, 1, 6);
    private static final BlockPos GONE = new BlockPos(4, 1, 4);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        failureDefersToCleanupAndDeliversOriginalReason();
        standingScaffoldEntersCleanupBeforeFailure();
        uncertainFailureKeepsSceneAndFailsImmediately();
        successWrapAlsoClearsTheLedger();
        emptyLedgerFailsImmediately();
        cancellationDefersToCleanupAndDeliversCancelled();
        cancellationDuringRunningCleanupKeepsDeliveringCancelled();
        cancellationWithoutTrackedScaffoldsTerminatesImmediately();
        navigationPlacedScaffoldEntersLedgerAndIsRecoveredOnCancellation();
        System.out.println("BuildTerminalScaffoldCleanupTest: terminal cleanup wrap passed");
    }

    /**
     * 184 的锚点——操作者取消不再跳过清理：台账里还有自有支撑时，取消请求转入仅清理缓期，
     * 清完账才交回 CANCELLED；清理阶段自己失败也不把取消改判成失败。
     */
    private static void cancellationDefersToCleanupAndDeliversCancelled() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            h.set(GONE, Blocks.DIRT.defaultBlockState());
            var task = task(h, GONE);
            h.set(GONE, Blocks.AIR.defaultBlockState());
            check(((FirstPersonBuildCompanionTask) task).requestCancellationCleanup(),
                    "an operator cancellation with tracked scaffolds requests the cleanup grace");
            check(phase(task).equals("SCAFFOLD_SELECT"), "the cancellation grace enters the cleanup phase");
            check(field(task, "terminalWrap").get(task).toString().equals("CANCELLED"),
                    "the cancellation wrap is armed instead of an immediate terminal state");
            check(invokeSelect(task) == TaskState.CANCELLED,
                    "after the ledger clears the task is delivered as CANCELLED, not FAILED");
            check(record(task).scaffoldLedger().isEmpty(), "the cancellation grace empties the ledger");
            check(field(task, "failureCode").get(task) == null,
                    "a cancelled cleanup never grows a failure code");
        }
    }

    /** 取消落在正在进行的清理阶段时，不重入清理：直接改包裹终点，让现有回收跑到取消收场。 */
    private static void cancellationDuringRunningCleanupKeepsDeliveringCancelled() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            h.set(GONE, Blocks.DIRT.defaultBlockState());
            var task = task(h, GONE);
            h.set(GONE, Blocks.AIR.defaultBlockState());
            setPhase(task, "SCAFFOLD_SELECT");
            field(task, "scaffoldQueue").set(task, List.of(GONE));
            check(((FirstPersonBuildCompanionTask) task).requestCancellationCleanup(),
                    "a cancellation landing inside the running cleanup is still deferred");
            check(invokeSelect(task) == TaskState.CANCELLED,
                    "the already-running cleanup settles straight into CANCELLED");
            check(record(task).scaffoldLedger().isEmpty(), "the running-cancellation wrap also clears the ledger");
        }
    }

    /** 台账为空（或还没开工）时取消按原路立即终态，不多占任何清理刻。 */
    private static void cancellationWithoutTrackedScaffoldsTerminatesImmediately() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            var task = task(h, null);
            check(!((FirstPersonBuildCompanionTask) task).requestCancellationCleanup(),
                    "without tracked scaffolds the cancellation is not deferred");
            check(field(task, "terminalWrap").get(task).toString().equals("NONE"),
                    "no wrap is armed without a ledger");
        }
    }

    /**
     * 批七实机锚点——导航期垫的支撑原先只进位置名单不进台账，取消缓期的台账空守卫直接放行立即取消，
     * 五块支撑永久残留。修复后 drainScaffolds 按现场方块状态核验补记台账，取消缓期覆盖导航垫块，
     * 支撑被确认移除后账目结清、任务按 CANCELLED 交付。
     */
    private static void navigationPlacedScaffoldEntersLedgerAndIsRecoveredOnCancellation() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            var task = task(h, null);
            // 模拟旧移动放置流：只登记位置名单，方块随后真实落地，没有原生确认回执。
            BuildPlacementRegistry.register(h.player, (FirstPersonBuildCompanionTask) task);
            BuildPlacementRegistry.recordScaffold(h.player, STANDING);
            h.set(STANDING, Blocks.DIRT.defaultBlockState());
            invoke(task, "drainScaffolds");
            BuildPlacementRegistry.unregister(h.player, (FirstPersonBuildCompanionTask) task);
            check(record(task).scaffoldLedger().contains(STANDING),
                    "a navigation-placed scaffold enters the ledger once the world confirms the block");
            check(((FirstPersonBuildCompanionTask) task).requestCancellationCleanup(),
                    "the cancellation grace now covers the navigation-placed scaffold");
            check(field(task, "terminalWrap").get(task).toString().equals("CANCELLED"),
                    "the cancellation wrap is armed for the navigation scaffold");
            setPhase(task, "SCAFFOLD_SELECT");
            field(task, "scaffoldQueue").set(task, List.of(STANDING));
            check(invokeSelect(task) == TaskState.RUNNING, "the recycle engages the standing scaffold");
            // 本夹具不执行原生破坏；模拟世界侧确认移除后，生产确认入口结清账目并交付取消。
            h.set(STANDING, Blocks.AIR.defaultBlockState());
            invoke(task, "confirmedScaffoldBreak");
            check(invokeSelect(task) == TaskState.CANCELLED,
                    "after the navigation scaffold is settled the task is delivered as CANCELLED");
            check(record(task).scaffoldLedger().isEmpty(), "the navigation scaffold left the ledger");
            check(field(task, "failureCode").get(task) == null,
                    "a recycled navigation scaffold never grows a failure code");
        }
    }

    private static void setPhase(Object task, String name) throws Exception {
        for (Class<?> inner : FirstPersonBuildCompanionTask.class.getDeclaredClasses())
            if (inner.getSimpleName().equals("Phase")) {
                field(task, "phase").set(task, Enum.valueOf((Class) inner, name));
                return;
            }
        throw new NoSuchFieldException("Phase");
    }

    /** 台账里有已消失支撑时，失败先被推迟，清完账再按原始失败原因交出 FAILED。 */
    private static void failureDefersToCleanupAndDeliversOriginalReason() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            h.set(GONE, Blocks.DIRT.defaultBlockState());
            var task = task(h, GONE);
            h.set(GONE, Blocks.AIR.defaultBlockState());
            failAt(task, GONE, "the target changed hands", FailureType.TARGET_LOST, "original_failure_code", false);
            check(phase(task).equals("SCAFFOLD_SELECT"), "a failure with tracked scaffolds first enters the cleanup phase");
            check(field(task, "failureCode").get(task) == null, "the original failure is deferred, not delivered yet");
            check(invokeSelect(task) == TaskState.FAILED, "after the ledger clears, the wrapped failure is delivered");
            check("original_failure_code".equals(field(task, "failureCode").get(task)),
                    "the delivered failure keeps the original code, not a cleanup-phase code");
            check(record(task).scaffoldLedger().isEmpty(), "cleared entries leave the ledger empty at delivery");
            check(number(task, "scaffoldCleanupPasses") == 1, "the cleanup pass actually ran before delivery");
        }
    }

    /** 还立在世界的支撑先进入真实清理阶段；支撑随后消失时同样以原失败收场。 */
    private static void standingScaffoldEntersCleanupBeforeFailure() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            h.set(STANDING, Blocks.DIRT.defaultBlockState());
            var task = task(h, STANDING);
            failAt(task, STANDING, "placement stance lost", FailureType.NO_PATH, "original_failure_code", false);
            check(phase(task).equals("SCAFFOLD_SELECT"), "a standing scaffold defers the failure to cleanup");
            check(invokeSelect(task) == TaskState.RUNNING, "the cleanup pass engages instead of failing");
            String engaged = phase(task);
            check(engaged.startsWith("SCAFFOLD_"), "cleanup scheduling took over: " + engaged);
            // 支撑被外界移除后，清理阶段按空气结算账目，再交付原失败。
            h.set(STANDING, Blocks.AIR.defaultBlockState());
            int guard = 0;
            while (guard++ < 16) {
                TaskState state = stepCleanup(task);
                if (state == TaskState.FAILED) break;
            }
            check("original_failure_code".equals(field(task, "failureCode").get(task)),
                    "the standing-scaffold wrap still delivers the original failure code");
            check(record(task).scaffoldLedger().isEmpty(), "the vanished scaffold is settled out of the ledger");
        }
    }

    /** UNCERTAIN 失败不包裹：保留现场供人工核验，不在未定结果上继续改世界。 */
    private static void uncertainFailureKeepsSceneAndFailsImmediately() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            h.set(STANDING, Blocks.DIRT.defaultBlockState());
            var task = task(h, STANDING);
            failAt(task, STANDING, "click outcome uncertain", FailureType.UNKNOWN, "uncertain_code", true);
            check(field(task, "failureCode").get(task).equals("uncertain_code"),
                    "an uncertain failure terminates immediately with its own code");
            check(field(task, "terminalWrap").get(task).toString().equals("NONE"),
                    "uncertain failures never enter the cleanup wrap");
            check(record(task).scaffoldLedger().contains(STANDING),
                    "the scene is preserved for manual verification");
        }
    }

    /** 绕过验证的直达成功同样先清账：成功包裹清完账后按 SUCCESS 交付。 */
    private static void successWrapAlsoClearsTheLedger() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            h.set(GONE, Blocks.DIRT.defaultBlockState());
            var task = task(h, GONE);
            h.set(GONE, Blocks.AIR.defaultBlockState());
            Method begin = task.getClass().getDeclaredMethod("beginTerminalCleanup",
                    terminalWrapClass(), BlockPos.class, String.class, FailureType.class, String.class, boolean.class);
            begin.setAccessible(true);
            Object success = Enum.valueOf((Class) terminalWrapClass(), "SUCCESS");
            check(Boolean.TRUE.equals(begin.invoke(task, success,
                    null, null, null, null, false)),
                    "a success wrap engages when tracked scaffolds remain");
            check(phase(task).equals("SCAFFOLD_SELECT"), "the success wrap runs the same cleanup phase");
            check(invokeSelect(task) == TaskState.SUCCESS,
                    "after the ledger clears the wrapped success is delivered");
            check(record(task).scaffoldLedger().isEmpty(), "the success wrap also empties the ledger");
        }
    }

    /** 台账为空时失败按原路立即交付，不多跑任何清理刻。 */
    private static void emptyLedgerFailsImmediately() throws Exception {
        try (var h = scene(new Vec3(3.5, 1, 6.5))) {
            h.position(new Vec3(3.5, 1, 6.5));
            var task = task(h, null);
            failAt(task, STANDING, "no scaffold here", FailureType.NO_PATH, "plain_code", false);
            check(field(task, "failureCode").get(task).equals("plain_code"),
                    "without tracked scaffolds the failure is delivered immediately");
            check(field(task, "terminalWrap").get(task).toString().equals("NONE"), "no wrap without a ledger");
        }
    }

    // ------------------------------------------------------------------
    // 夹具：带台账的施工任务，scaffoldQueue 由 beginTerminalCleanup 现场构建。
    // ------------------------------------------------------------------

    private static InteractionWorldTestHarness scene(Vec3 feet) throws Exception {
        var world = new InteractionWorldTestHarness(); world.position(feet);
        // 原生距离与碰撞查询读取实体的尺寸缓存与游戏模式；此夹具不建立网络连接。
        sun.misc.Unsafe memory = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        Object info = memory.allocateInstance(net.minecraft.client.multiplayer.PlayerInfo.class);
        field(net.minecraft.client.multiplayer.PlayerInfo.class, "gameMode").set(info, net.minecraft.world.level.GameType.SURVIVAL);
        field(net.minecraft.client.player.AbstractClientPlayer.class, "playerInfo").set(world.player, info);
        field(net.minecraft.world.entity.Entity.class, "dimensions").set(world.player, net.minecraft.world.entity.EntityDimensions.scalable(.6F, 1.8F));
        return world;
    }

    private static FirstPersonBuildCompanionTask task(InteractionWorldTestHarness h, BlockPos tracked) throws Exception {
        var record = new BuildTaskRecord("terminal-cleanup", 1000, List.of(), false);
        if (tracked != null) record.scaffoldLedger().confirmed(tracked, h.level.getBlockState(tracked));
        var task = new FirstPersonBuildCompanionTask(h.player, record);
        field(task, "preflightDone").setBoolean(task, true);
        if (tracked != null) {
            @SuppressWarnings("unchecked") var scaffolds = (java.util.Set<BlockPos>) field(task, "scaffolds").get(task);
            scaffolds.add(tracked);
        }
        return task;
    }
    private static void failAt(Object task, BlockPos pos, String message, FailureType type, String code,
                               boolean unknown) throws Exception {
        Method fail = task.getClass().getDeclaredMethod("failAt",
                BlockPos.class, String.class, FailureType.class, String.class, boolean.class);
        fail.setAccessible(true);
        fail.invoke(task, pos, message, type, code, unknown);
    }
    private static TaskState invokeSelect(Object task) throws Exception {
        return (TaskState) invoke(task, "scaffoldSelectTick");
    }
    /** 按当前清理阶段推进一步；SCAFFOLD_SELECT 以外的阶段循环驱动对应 tick。 */
    private static TaskState stepCleanup(Object task) throws Exception {
        String current = phase(task);
        return switch (current) {
            case "SCAFFOLD_SELECT" -> invokeSelect(task);
            case "SCAFFOLD_NAV" -> (TaskState) invoke(task, "scaffoldNavTick");
            case "SCAFFOLD_BREAK" -> (TaskState) invoke(task, "scaffoldBreakTick");
            default -> TaskState.FAILED;
        };
    }
    private static String phase(Object task) throws Exception { return field(task, "phase").get(task).toString(); }
    private static BuildTaskRecord record(Object task) throws Exception { return (BuildTaskRecord) field(task, "r").get(task); }
    private static int number(Object task, String name) throws Exception { return field(task, name).getInt(task); }
    private static Object invoke(Object task, String name) throws Exception {
        Method method = task.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static Class<?> terminalWrapClass() throws Exception {
        for (Class<?> inner : FirstPersonBuildCompanionTask.class.getDeclaredClasses())
            if (inner.getSimpleName().equals("TerminalWrap")) return inner;
        throw new NoSuchFieldException("TerminalWrap");
    }
    private static Field field(Object instance, String name) throws Exception { return field(instance.getClass(), name); }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) try {
            Field field = current.getDeclaredField(name); field.setAccessible(true); return field;
        } catch (NoSuchFieldException inherited) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
