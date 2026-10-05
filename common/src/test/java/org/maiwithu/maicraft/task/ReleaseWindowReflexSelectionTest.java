// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

import java.util.List;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
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
            Task settleLike = new StubReflex("settle_like", true, false);
            Task rescue = new StubReflex("rescue", false, false);
            Task urgentLike = new StubReflex("urgent_like", true, true);

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
        }
        System.out.println("ReleaseWindowReflexSelectionTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 只参与选人排序的桩反射：canRun 恒真，不触碰玩家状态。 */
    private record StubReflex(String label, boolean releaseWindow, boolean urgent) implements Task {
        @Override public boolean canRun(LocalPlayer companion) { return true; }
        @Override public boolean onlyWhenBodyReleased() { return releaseWindow; }
        @Override public boolean urgentBodyRescue(LocalPlayer companion) { return urgent; }
        @Override public TaskState tick(LocalPlayer companion) { return TaskState.RUNNING; }
        @Override public void stop(LocalPlayer companion, StopReason why) { }
        @Override public String name() { return label; }
    }
}
