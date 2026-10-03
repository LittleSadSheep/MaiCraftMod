package org.maiwithu.maicraft.core.integration.physics.balance;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import org.joml.Quaterniond;
import static org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceRegression.*;

/** 检验滑跑、侧滑、转弯和停桨时的载荷变化，避免静态箭头被当成固定翼全程可用的升力。 */
final class PhysicsAerodynamicsTest {
    static void run() {
        var sail=new PhysicsAerodynamics(v(0,-1,0),.75,.06888202261,.475,1,.01);
        near(sail.force(PhysicsVector.ZERO).length(),0,"没有迎流时帆面不能凭空托起飞机");
        var forward=sail.force(v(10,0,0));
        near(forward.y(),4.75,"水平帆面的原生升力方向或系数错误");
        near(forward.x(),-.6888202261,"沿航向的阻力没有抵消前进速度");
        near(sail.force(v(20,0,0)).y(),9.5,"加速后必须重新计算原生升力");
        // 对称帆只增加法向阻力，不应被预测成能够提供单向升力的机翼。
        var symmetric=new PhysicsAerodynamics(v(0,1,0),1.75,.06888202261,0,1,.01);
        near(symmetric.force(v(10,0,0)).y(),0,"对称帆被错误当成升力翼");
        check(symmetric.force(v(0,-2,0)).y()>0,"下降时帆面阻力应阻止下降");
        var load=new PhysicsBody.Load("wing","sable:aerodynamic_surface",v(2,0,0),PhysicsVector.ZERO,
                PhysicsVector.ZERO,PhysicsBody.Frame.BODY,false,0,0,sail);
        var body=vessel(List.of(load));
        var stopped=PhysicsWrench.evaluate(body,body.rotation(),body.position(),v(10,0,0),PhysicsVector.ZERO,Map.of(),0,true);
        near(stopped.contributions().get(1).forceWorld().y(),4.75,"停桨滑行仍应保留机翼升力");
        near(stopped.torque().z(),9.5,"偏置机翼的力矩必须围绕质心求取");
        var turn=PhysicsBody.Rotation.of(new Quaterniond().rotationY(Math.PI/2));
        var turned=PhysicsWrench.evaluate(body,turn,body.position(),turn.world(v(10,0,0)),PhysicsVector.ZERO,Map.of(),1,true);
        near(turned.torque().x(),9.5,"转向后的机翼力矩没有随结构旋转");
        var rotating=PhysicsWrench.evaluate(body,body.rotation(),body.position(),PhysicsVector.ZERO,v(0,0,1),Map.of(),0,true);
        check(rotating.contributions().get(1).forceWorld().length()>0,"旋转中的翼尖速度不能被静止质心速度掩盖");
        var gson=new Gson();var restored=gson.fromJson(gson.toJson(body),PhysicsBody.class);
        near(restored.loads().getFirst().aerodynamics().force(v(10,0,0)).y(),4.75,"快照分页序列化丢失了动态帆面参数");
        var cruise=body.movingAt(v(10,0,0));
        var assessment=PhysicsSimulation.assess(cruise,new PhysicsSimulation.Limits(1,8,.25,.035,2),Map.of());
        near(assessment.stopped().contributions().get(1).forceWorld().y(),0,"停车工况错误继承巡航升力");
        near(assessment.trials().stream().filter(t->t.mode().equals("running")&&t.perturbation().equals("none")).findFirst().orElseThrow()
                .trajectory().getFirst().velocity().x(),10,"运行工况没有从声明航速开始");
        check(assessment.trials().stream().filter(t->t.mode().equals("starting")).allMatch(t->t.trajectory().getFirst().velocity().length()==0),"起步场景跳过静止滑跑");
        near(body.velocity().length(),0,"设置预测航速污染了原始观察");
        // 原生阻力组混有其他来源时，动态帆面只贡献与采样时的差值，不能顺便删掉水阻或重复计入旧升力。
        var mixed=new PhysicsBody.Load("native_drag","sable:drag",PhysicsVector.ZERO,forward.add(v(-3,0,0)),PhysicsVector.ZERO,PhysicsBody.Frame.BODY,false,0);
        var delta=new PhysicsBody.Load("airfoil_delta","sable:aerodynamics_delta",PhysicsVector.ZERO,forward.scale(-1),PhysicsVector.ZERO,
                PhysicsBody.Frame.BODY,false,0,0,sail);
        var anchored=vessel(List.of(mixed,delta));
        var accelerated=PhysicsWrench.evaluate(anchored,anchored.rotation(),anchored.position(),v(20,0,0),PhysicsVector.ZERO,Map.of(),1,true);
        near(accelerated.force().x(),-4.3776404522,"动态升力替换误删了其他原生阻力");
        near(accelerated.force().y(),-90.5,"动态升力与采样升力被重复计算");
        var lifting=new PhysicsBody.Load("center_wing","sable:aerodynamic_surface",PhysicsVector.ZERO,PhysicsVector.ZERO,
                PhysicsVector.ZERO,PhysicsBody.Frame.BODY,false,0,0,new PhysicsAerodynamics(v(0,-1,0),0,0,10,1,.01));
        var flying=vessel(List.of(lifting)).movingAt(v(10,0,0));
        var recommendation=PhysicsTrim.recommend(flying,List.of(),0,Map.of(),new PhysicsSimulation.Limits(1,8,.25,.035,2));
        near(recommendation.beforeScore(),1600,"推荐器把巡航升力借给了停稳工况");
    }
}
