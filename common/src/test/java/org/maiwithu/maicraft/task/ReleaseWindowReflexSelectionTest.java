// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

import java.util.List;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.chain.BreathChain;
import org.maiwithu.maicraft.core.task.chain.SettleChain;

/**
 * 释放窗口反射的选人规则：在岗任务持有身体时，贴边退避这类释放窗口反射不参与
 * 抢占（贴边站位是任务的正当姿态），普通自救照常优先；身体释放后恢复第一顺位；
 * 围困窒息这类紧急自救豁免在身体被持有时照样接管。另一条同时修的短路：任务槽
 * 已结算而 holder 还停在槽代理上时，不得再按"在岗持有"把释放窗口反射挡在门外。
 */
public final class ReleaseWindowReflexSelectionTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            LocalPlayer player = world.player;
            Task settleLike = new StubReflex("settle_like", true, false, false, false);
            Task rescue = new StubReflex("rescue", false, false, false, false);
            Task urgentLike = new StubReflex("urgent_like", true, true, false, false);

            check(new SettleChain().onlyWhenBodyReleased(), "贴边安定反射必须声明为释放窗口反射");
            check(!rescue.onlyWhenBodyReleased(), "普通自救反射不得默认声明释放窗口");

            // 在岗任务持有身体：释放窗口反射让位，排在其后的普通自救照常胜出。
            check(TaskSelector.select(List.of(settleLike, rescue), null, null, List.of(), player, true) == rescue,
                    "在岗任务持有身体时，释放窗口反射不得抢占，普通自救照常参与");

            // 紧急自救豁免：声明释放窗口但此刻身体处境等不起（围困窒息的调度形态）的
            // 反射，在身体仍被持有时也必须胜出——修复前被释放窗口判定直接跳过，
            // 眼位被塞的旧形态下窒息无人接管。
            check(TaskSelector.select(List.of(urgentLike, rescue), null, null, List.of(), player, true) == urgentLike,
                    "紧急自救豁免的反射在身体被持有时照样接管");

            // 身体已释放：释放窗口反射按注册序恢复第一顺位。
            check(TaskSelector.select(List.of(settleLike, rescue), null, null, List.of(), player, false) == settleLike,
                    "身体释放后释放窗口反射恢复第一顺位");

            // 只有释放窗口反射可跑而身体被任务持有时，不得为它换手——结果为空，身体留给在岗任务。
            check(TaskSelector.select(List.of(settleLike), null, null, List.of(), player, true) == null,
                    "身体被在岗任务持有时释放窗口反射不应胜出");

            // 连续驾驶让位：任务靠连续输入驾驶身体（水中攀沿等）时，声明让位的非致命反射
            // 暂不参与抢占——按秒切碎的驾驶永远凑不齐所需输入；普通反射与紧急豁免照常。
            Task driver = new StubReflex("driver", false, false, true, false);
            Task breathing = new StubReflex("breathing", false, false, false, true);
            Task urgentBreathing = new StubReflex("urgent_breathing", false, true, false, true);
            check(TaskSelector.select(List.of(breathing, rescue), null, driver, List.of(), player, true) == rescue,
                    "连续驾驶期间声明让位的反射不得抢占，普通反射照常参与");
            check(TaskSelector.select(List.of(urgentBreathing, rescue), null, driver, List.of(), player, true) == urgentBreathing,
                    "紧急自救豁免不受连续驾驶让位约束");
            Task idleStance = new StubReflex("not_driving", false, false, false, false);
            check(TaskSelector.select(List.of(breathing, rescue), null, idleStance, List.of(), player, true) == breathing,
                    "驾驶结束后让位反射恢复第一顺位");
            check(new BreathChain().yieldsToContinuousDriving(),
                    "换气反射必须声明连续驾驶让位（爬沿驾驶被按秒切碎的实机形态）");

            // 5 参旧入口等价于身体已释放，既有调用方的语义不变。
            check(TaskSelector.select(List.of(settleLike, rescue), null, null, List.of(), player) == settleLike,
                    "旧入口视身体为已释放，释放窗口反射照常参与");

            // 陈旧持有短路：任务槽已结算（槽空）而 holder 仍指向槽代理时，
            // 不得视为在岗持有，否则释放窗口反射被一个已不存在的任务无限期挡住。
            CompanionBrain brain = new CompanionBrain();
            Field holderField = CompanionBrain.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            Field proxyField = CompanionBrain.class.getDeclaredField("currentProxy");
            proxyField.setAccessible(true);
            holderField.set(brain, proxyField.get(brain));
            check(!brain.slotHoldsBody(),
                    "任务槽已空时，残留在 holder 上的槽代理不算在岗持有");

            // 换手拒绝留痕的冷却判定：新建的脑从未留痕过，任意游戏刻的首次判定必须放行。
            // 限频初值曾取 Long.MIN_VALUE，now - last 在首次判定即回绕为负，冷却恒成立，
            // 留痕一次都打不出来——这里以任意典型游戏刻复现该形态并锁住修复。
            CompanionBrain refusalBrain = new CompanionBrain();
            check(refusalBrain.yieldRefusalLogDue(1000L),
                    "首次换手拒绝必须立即留痕，不得被冷却初值挡住");
            // 留痕发生后 logYieldRefusal 会写入当前刻；这里写入同一值模拟真实流程。
            Field refusalStamp = CompanionBrain.class.getDeclaredField("lastYieldRefusalLog");
            refusalStamp.setAccessible(true);
            refusalStamp.setLong(refusalBrain, 1000L);
            check(!refusalBrain.yieldRefusalLogDue(1000L + 99),
                    "冷却窗口内的后续拒绝按限频跳过留痕");
            check(refusalBrain.yieldRefusalLogDue(1000L + 100),
                    "冷却窗口过后恢复留痕资格");
        }
        System.out.println("ReleaseWindowReflexSelectionTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 只参与选人排序的桩反射：canRun 恒真，不触碰玩家状态。 */
    private record StubReflex(String label, boolean releaseWindow, boolean urgent,
                              boolean driving, boolean yields) implements Task {
        @Override public boolean canRun(LocalPlayer companion) { return true; }
        @Override public boolean onlyWhenBodyReleased() { return releaseWindow; }
        @Override public boolean urgentBodyRescue(LocalPlayer companion) { return urgent; }
        @Override public boolean drivesBodyContinuously(LocalPlayer companion) { return driving; }
        @Override public boolean yieldsToContinuousDriving() { return yields; }
        @Override public TaskState tick(LocalPlayer companion) { return TaskState.RUNNING; }
        @Override public void stop(LocalPlayer companion, StopReason why) { }
        @Override public String name() { return label; }
    }
}
