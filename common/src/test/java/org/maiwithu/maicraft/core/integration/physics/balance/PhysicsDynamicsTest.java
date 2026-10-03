package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import org.maiwithu.maicraft.intent.SemanticResultView;
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
        check(trim.proposedBallast().size() == 1 && trim.validation().predictedBalanced(), "配重应同时补足质量并对齐浮力作用线");
        // 配重是供模型评估的语义方案，不应被旧的内部放置脚本字段过滤掉材料、作用点或质量。
        var gson=new Gson();var visible=gson.toJsonTree(SemanticResultView.data(Map.of("recommendation",gson.toJsonTree(trim)))).getAsJsonObject();
        var proposed=visible.getAsJsonObject("recommendation").getAsJsonArray("proposedBallast");
        check(proposed!=null&&proposed.size()==1&&proposed.get(0).getAsJsonObject().get("mass").getAsDouble()==2
                &&proposed.get(0).getAsJsonObject().getAsJsonObject("point").get("x").getAsDouble()==6,"对外回执丢失配重候选细节");
        check(trim.afterScore() < trim.beforeScore(), "推荐应改善两个工况的最差偏差");
        var cruisePropeller=new PhysicsBody.Load("cruise","propulsion",v(0,0,0),v(100,0,0),PhysicsVector.ZERO,
                PhysicsBody.Frame.BODY,true,0,10);
        var craft=vessel(List.of(cruisePropeller));
        near(PhysicsWrench.evaluate(craft,craft.rotation(),craft.position(),v(5,0,0),PhysicsVector.ZERO,Map.of(),1,false).force().x(),
                50,"达到一半桨流速度后推力应按原生迎流规则减半");
        var missing=new PhysicsBody(craft.structureId(),craft.dimension(),craft.tick(),craft.mass(),craft.center(),craft.inertia(),craft.rotation(),
                craft.position(),craft.velocity(),craft.angularVelocity(),craft.gravity(),List.of(balloon),List.of("unmodeled:未知推进器"));
        check(!PhysicsSimulation.assess(missing,limits,Map.of()).predictedBalanced(),"漏掉推进器后不能把剩余载荷的平衡当成完整结论");
        var brief=new PhysicsSimulation.Limits(1,8,.25,.035,2);
        var stopped=PhysicsSimulation.assess(vessel(List.of(balloon)),brief,Map.of()).trials().stream()
                .filter(trial->trial.mode().equals("stopping")).findFirst().orElseThrow();
        near(stopped.trajectory().getLast().seconds(),4,"一秒观察窗口仍须包含完整的停机过渡");
        try(var budget=PhysicsComputation.begin(()->true)) {
            try { wrench(craft,craft.rotation(),1);throw new AssertionError("取消后试算仍在继续"); }
            catch(PhysicsComputation.Limit expected) { }
        }
        wrench(craft,craft.rotation(),1);
    }
}
