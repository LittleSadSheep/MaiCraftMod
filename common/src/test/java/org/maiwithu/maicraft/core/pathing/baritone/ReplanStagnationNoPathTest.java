// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.event.events.PathEvent;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 无法满足的目标带靠重规划震荡快速给出诚实无路结论：同一目标、同一站位、零确认进展的连续重算
 * 到达限额即收场并携带归因；位移、确认动作或目标变化都会重置计数，正常目标不受误杀。
 */
public final class ReplanStagnationNoPathTest {
    private static final int LIMIT = 60;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        oscillationFailsFastWithAttribution();
        progressAndGoalChangeResetTheCounter();
        System.out.println("ReplanStagnationNoPathTest: passed");
    }

    /** 连续零进展重算到达限额后，导航在安全边界给出 NO_PATH，原因以 unsatisfiable_target_band 归因开头。 */
    private static void oscillationFailsFastWithAttribution() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var compiled = GoalCompiler.standOn(new BlockPos(12, 1, 12));
            var nav = new EmbeddedBaritoneNavigator(world.player, () -> compiled, () -> false,
                    PlayerNav.ContextProvider.DEFAULT, false);
            fingerprint(nav, compiled.semanticFingerprint());
            for (int i = 1; i < LIMIT; i++) nav.onPathEvent(PathEvent.CALC_STARTED);
            check(notFailed(nav), "限额以内不提前收场");
            nav.onPathEvent(PathEvent.CALC_STARTED);
            check(nav.tick() == PlayerNav.Status.FAILED, "限额后导航终局为 FAILED");
            check(nav.failType() == FailureType.NO_PATH, "失败类型是无路而非规划停滞");
            check(nav.failReason().startsWith("unsatisfiable_target_band:"),
                    "原因必须携带 unsatisfiable_target_band 归因供调用方区分");
            check(nav.failReason().contains(LIMIT + " consecutive route calculations"),
                    "原因携带已尝试次数证据");
            check(nav.failReason().contains("not evidence that no route exists from another position"),
                    "缺席不等于不存在：必须声明不构成其他位置无路的证据");
            // 事件继续到来不再改写已锁存的终局。
            nav.onPathEvent(PathEvent.CALC_STARTED);
            check(nav.failReason().startsWith("unsatisfiable_target_band:"), "终局原因不被后续事件覆盖");
        }
    }

    /** 位移、确认动作与目标变化都清零计数：正常行走、原生动作或换目标不触发快速收场。 */
    private static void progressAndGoalChangeResetTheCounter() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var compiled = GoalCompiler.standOn(new BlockPos(12, 1, 12));
            var nav = new EmbeddedBaritoneNavigator(world.player, () -> compiled, () -> false,
                    PlayerNav.ContextProvider.DEFAULT, false);
            fingerprint(nav, compiled.semanticFingerprint());
            for (int i = 0; i < LIMIT - 1; i++) nav.onPathEvent(PathEvent.CALC_STARTED);
            // 物理位移重置：脚位变化后重新起算。
            world.position(new Vec3(0.5, 1, 0.5));
            nav.onPathEvent(PathEvent.CALC_STARTED);
            for (int i = 1; i < LIMIT - 1; i++) nav.onPathEvent(PathEvent.CALC_STARTED);
            check(notFailed(nav), "位移后的重算重新起算，不累计旧震荡");
            // 确认动作重置：原生操作进展同样是真实进展事实。
            nav.recordConfirmedNativeAction();
            nav.onPathEvent(PathEvent.CALC_STARTED);
            for (int i = 1; i < LIMIT - 1; i++) nav.onPathEvent(PathEvent.CALC_STARTED);
            check(notFailed(nav), "确认的原生动作重置震荡计数");
            // 目标变化重置：新目标是新问题，不继承旧目标的震荡历史。
            var revised = GoalCompiler.standOn(new BlockPos(20, 1, 20));
            fingerprint(nav, revised.semanticFingerprint());
            nav.onPathEvent(PathEvent.CALC_STARTED);
            for (int i = 1; i < LIMIT - 1; i++) nav.onPathEvent(PathEvent.CALC_STARTED);
            check(notFailed(nav), "目标变化后旧震荡历史不带入新目标");
        }
    }

    private static boolean notFailed(EmbeddedBaritoneNavigator nav) {
        return "embedded pathing has not failed".equals(nav.failReason());
    }

    private static void fingerprint(EmbeddedBaritoneNavigator nav, GoalCompiler.CompiledFingerprint fingerprint)
            throws Exception {
        Field field = EmbeddedBaritoneNavigator.class.getDeclaredField("compiledFingerprint");
        field.setAccessible(true);
        field.set(nav, fingerprint);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
