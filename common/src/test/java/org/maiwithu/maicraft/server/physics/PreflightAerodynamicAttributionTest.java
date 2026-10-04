package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;

/** 回放固定翼图解漏掉的微小力及合并箭头力偶；匹配时只改归属，不能改数值或吞掉额外冲量。 */
public final class PreflightAerodynamicAttributionTest {
    public static void run() {
        var force=new PhysicsVector(3.752797510633496e-5,.05013740966342084,-.007929101323095822);
        var torque=new PhysicsVector(-.0970766802962757,.005135069129905474,-1.3594809828987309e-6);
        var observed=new PhysicsBody.Load("unattributed_impulse","sable:unattributed_impulse",PhysicsVector.ZERO,
                force,torque,PhysicsBody.Frame.BODY,false,0);
        var loads=new ArrayList<>(List.of(observed));var unknowns=unknowns();
        var computedForce=new PhysicsVector(3.752797510633518e-5,.05013740966340663,-.007929101323095877);
        var computedTorque=new PhysicsVector(-.09707668029625882,.005135069129905544,-1.3594810366335253e-6);
        check(PreflightAerodynamics.attribute(loads,unknowns,computedForce,computedTorque),"真实采样中的图解残差应被完整核对");
        check(loads.getFirst().force().equals(force)&&loads.getFirst().torque().equals(torque),"归属不能用预测值覆盖原生数值");
        check(loads.getFirst().group().equals("sable:aerodynamics_sample")&&unknowns.equals(List.of("unmodeled:其他来源仍未知")),"只删除已核验的气动未知项");

        // 即使只多出很小的外部力，仍保留未归属观察；不能为了让配平显示通过而放宽完整性。
        for(var extra:List.of(new PhysicsVector(.001,0,0),new PhysicsVector(0,.001,0))) {
            loads=new ArrayList<>(List.of(observed));unknowns=unknowns();
            check(!PreflightAerodynamics.attribute(loads,unknowns,force.add(extra),torque),"多余外力不能被归为帆面");
            check(loads.getFirst()==observed&&unknowns.contains(NativePhysicsCapture.UNATTRIBUTED_WARNING),"不匹配时必须保留完整原观察");
        }
        loads=new ArrayList<>(List.of(observed));unknowns=unknowns();
        check(!PreflightAerodynamics.attribute(loads,unknowns,force,torque.add(new PhysicsVector(0,.001,0))),"未知力偶不能被合力匹配掩盖");
        check(!PreflightAerodynamics.attribute(new ArrayList<>(),unknowns(),force,torque),"缺少原生残差证据不能声称核对完成");
        check(!PreflightAerodynamics.attribute(new ArrayList<>(),unknowns(),PhysicsVector.ZERO,PhysicsVector.ZERO),"残差载荷丢失时不能仅凭零预测清除未知标记");
        check(PreflightAerodynamics.attribute(new ArrayList<>(),new ArrayList<>(),PhysicsVector.ZERO,PhysicsVector.ZERO),"无遗漏的图解不应凭空增加残差");
        System.out.println("PreflightAerodynamicAttributionTest: passed");
    }
    private static ArrayList<String> unknowns() {
        return new ArrayList<>(List.of(NativePhysicsCapture.UNATTRIBUTED_WARNING,"sable:lift 使用采样时的气动载荷，速度变化后需要重新观察",
                "sable:drag 使用采样时的气动载荷，速度变化后需要重新观察","unmodeled:其他来源仍未知"));
    }
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
}
