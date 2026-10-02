package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import java.util.Map;
import static org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceRegression.*;

/** 运行时能托住船体却无法停机的设计必须被识别；配重建议同时接受关桨与开桨检验。 */
final class PhysicsDynamicsTest {
    static void run() {
        var limits = PhysicsSimulation.Limits.defaults();
        var balloon = new PhysicsBody.Load("balloon", "balloon_lift", v(0, 2, 0), v(0, 100, 0),
                PhysicsVector.ZERO, PhysicsBody.Frame.WORLD, false, 0);
        var balanced = PhysicsSimulation.assess(vessel(List.of(balloon)), limits, Map.of());
        check(balanced.predictedBalanced(), "上方持续浮力应在停机、启动、运行、停转及四向扰动下保持平衡");
        check(!balanced.nativeVerified(), "数值预测不得冒充实船验收");
        check(balanced.trials().size() == 12, "必须覆盖四种工况和双工况四向扰动");
        var propeller = new PhysicsBody.Load("propeller", "propulsion", v(0, 2, 0), v(0, 100, 0),
                PhysicsVector.ZERO, PhysicsBody.Frame.BODY, true, .4);
        var airborne = PhysicsSimulation.assess(vessel(List.of(propeller)), limits, Map.of());
        check(!airborne.stoppedEquilibrium() && airborne.runningEquilibrium(), "仅靠桨升空时不能承诺关桨悬停");
        check(!airborne.predictedBalanced(), "关桨会下坠的设计不能通过启停配平");
        var inverted = new PhysicsBody.Load("low_balloon", "balloon_lift", v(0, -2, 0), v(0, 100, 0),
                PhysicsVector.ZERO, PhysicsBody.Frame.WORLD, false, 0);
        check(!PhysicsSimulation.assess(vessel(List.of(inverted)), limits, Map.of()).restoringStopped(),
                "浮力中心低于质心时，瞬间力矩为零仍可能翻船");
        var heavyLift = new PhysicsBody.Load("balloon", "balloon_lift", v(1, 2, 0), v(0, 120, 0),
                PhysicsVector.ZERO, PhysicsBody.Frame.WORLD, false, 0);
        var trim = PhysicsTrim.recommend(vessel(List.of(heavyLift)),
                List.of(new PhysicsTrim.Ballast("starboard", "minecraft:iron_block", v(6, -2, 0), 2, 1)),
                1, Map.of(), limits);
        check(trim.placements().size() == 1 && trim.validation().predictedBalanced(), "配重应同时补足质量并对齐浮力作用线");
        check(trim.afterScore() < trim.beforeScore(), "推荐应改善两个工况的最差偏差");
    }
}
