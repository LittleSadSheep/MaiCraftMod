// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.utils.PathCalculationResult;
import baritone.api.event.events.PathEvent;
import baritone.pathing.calc.AbstractNodeCostSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;
import org.maiwithu.maicraft.core.FailureType;
import baritone.pathing.calc.AStarPathFinder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import sun.misc.Unsafe;

/** 复算找到无地形改动的路径时继续原导航，持续无进展则有界结束，不把复算循环交给模型。 */
public final class PreserveProbeRecoveryTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var compiled = GoalCompiler.standOn(new BlockPos(12, 1, 12));
            var nav = new EmbeddedBaritoneNavigator(world.player, () -> compiled, () -> false, PlayerNav.ContextProvider.DEFAULT, false).withTerrainProbe();
            set(nav, "compiledFingerprint", compiled.semanticFingerprint()); set(nav, "plannedCenter", compiled.goal().center());
            var diagnose = EmbeddedBaritoneNavigator.class.getDeclaredMethod("diagnoseNoPath"); diagnose.setAccessible(true);
            var constructor = EmbeddedBaritoneTerrainProbe.ProbeFuture.class.getDeclaredConstructor(AbstractNodeCostSearch.class, long.class); constructor.setAccessible(true);
            for (int attempt = 0; attempt < 3; attempt++) {
                var probe = constructor.newInstance(null, 10_000L);
                probe.complete(new EmbeddedBaritoneTerrainProbe.Result(new PathCalculationResult(PathCalculationResult.Type.SUCCESS_TO_GOAL), new TerrainBill()));
                set(nav, "terrainProbe", probe); set(nav, "terrainProbeFingerprint", compiled.semanticFingerprint());
                set(nav, "terrainProbePolicy", EmbeddedBaritonePolicy.snapshot()); set(nav, "started", true); set(nav, "calculationFailed", true);
                Object state = diagnose.invoke(nav);
                check(state == (attempt < 2 ? PlayerNav.Status.RUNNING : PlayerNav.Status.FAILED), "复算恢复次数必须有限且无需重发语义目标");
                check(nav.permit() == TerrainPermit.PRESERVE, "复算不能扩大挖掘或搭路许可");
                if (attempt < 2) {
                    var started = EmbeddedBaritoneNavigator.class.getDeclaredField("started"); started.setAccessible(true);
                    check(!started.getBoolean(nav), "下一刻必须实际重新启动原导航");
                }
            }
        }
        // 旧起点的普通搜索已失败时，传送也必须让同一任务的只读复算失效并重新起步。
        try (var world = new InteractionWorldTestHarness()) {
            var compiled = GoalCompiler.standOn(new BlockPos(12, 1, 12));
            var nav = new EmbeddedBaritoneNavigator(world.player, () -> compiled, () -> false,
                    PlayerNav.ContextProvider.DEFAULT, false).withTerrainProbe();
            set(nav, "compiledFingerprint", compiled.semanticFingerprint()); set(nav, "plannedCenter", compiled.goal().center());
            set(nav, "started", true); nav.onPathEvent(PathEvent.CALC_FAILED);
            var constructor = EmbeddedBaritoneTerrainProbe.ProbeFuture.class.getDeclaredConstructor(AbstractNodeCostSearch.class, long.class);
            constructor.setAccessible(true); var probe = constructor.newInstance(null, 10_000L);
            probe.complete(new EmbeddedBaritoneTerrainProbe.Result(new PathCalculationResult(PathCalculationResult.Type.FAILURE), new TerrainBill()));
            set(nav, "terrainProbe", probe);
            world.position(new Vec3(8.5, 1, 8.5));
            var diagnose = EmbeddedBaritoneNavigator.class.getDeclaredMethod("diagnoseNoPath"); diagnose.setAccessible(true);
            check(diagnose.invoke(nav) == PlayerNav.Status.RUNNING, "传送后不能沿用旧复算失败");
            var started = EmbeddedBaritoneNavigator.class.getDeclaredField("started"); started.setAccessible(true);
            check(!started.getBoolean(nav) && nav.permit() == TerrainPermit.PRESERVE, "原任务在新脚位重算，通行许可不扩大");
        }
        stalledProbeRecoversOnce();
        System.out.println("PreserveProbeRecoveryTest: passed");
    }

    /** 从真实导航的无路诊断入口回放停滞复算，验证同一任务恢复一次后有界结束，许可和世界都不变。 */
    private static void stalledProbeRecoversOnce() throws Exception {
        var unsafe = Unsafe.class.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
        var memory = (Unsafe) unsafe.get(null);
        var constructor = EmbeddedBaritoneTerrainProbe.ProbeFuture.class.getDeclaredConstructor(AbstractNodeCostSearch.class, long.class);
        constructor.setAccessible(true);
        var release = new CountDownLatch(1);
        try (var world = new InteractionWorldTestHarness()) {
            var compiled = GoalCompiler.standOn(new BlockPos(12, 1, 12));
            var nav = new EmbeddedBaritoneNavigator(world.player, () -> compiled, () -> false, PlayerNav.ContextProvider.DEFAULT, false).withTerrainProbe();
            set(nav, "compiledFingerprint", compiled.semanticFingerprint()); set(nav, "plannedCenter", compiled.goal().center());
            var diagnose = EmbeddedBaritoneNavigator.class.getDeclaredMethod("diagnoseNoPath"); diagnose.setAccessible(true);
            for (int attempt = 0; attempt < 2; attempt++) {
                var probe = constructor.newInstance(memory.allocateInstance(AStarPathFinder.class), -1L);
                var started = new CountDownLatch(1);
                var work = PathPlannerPool.submit(() -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                    return null;
                });
                check(started.await(5, TimeUnit.SECONDS), "复算工作线程已启动");
                set(probe, "calculation", work); set(nav, "terrainProbe", probe);
                check(diagnose.invoke(nav) == (attempt == 0 ? PlayerNav.Status.RUNNING : PlayerNav.Status.FAILED), "复算只恢复一次且不无限等待");
                check(work.isCancelled() && nav.permit() == TerrainPermit.PRESERVE, "实际复算请求被退役且没有扩大许可");
            }
            check(nav.failType() == FailureType.PLANNING_STALL && nav.healthDiagnostics().get("component").equals("terrain_probe"), "复算停滞不能改判为地形无路");
            check(world.itemUses() == 0 && world.blockUses() == 0, "建议性复算恢复没有执行游戏交互");
        } finally { release.countDown(); }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
