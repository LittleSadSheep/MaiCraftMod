package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import com.google.gson.Gson;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import static org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceRegression.*;

/** 用四轮承重、刹停和单侧压缩校验地面配平，不能把停车等同于没有轮胎支撑的悬停。 */
final class PhysicsWheelDynamicsTest {
    static void run() {
        var source=vessel(List.of());
        var body=new PhysicsBody(source.structureId(),source.dimension(),source.tick(),10,PhysicsVector.ZERO,source.inertia(),source.rotation(),
                v(0,.5+.75+.65-.65/6-.0125,0),PhysicsVector.ZERO,PhysicsVector.ZERO,v(0,-10,0),List.of(),List.of());
        var point=v(1,-.5,1);var wheel=wheel(point,0,.75);
        near(force(body,point,wheel,PhysicsVector.ZERO,0).y(),25,"停动力后每个轮子仍需承受四分之一重量");
        near(force(body,point,wheel,PhysicsVector.ZERO,16).z(),28,"原生轮胎驱动转速与推力比例错误");
        near(force(body,point,wheel,v(0,0,10),0).z(),-75,"松动力后的滚动阻力丢失");
        near(force(body,point,wheel(point,1,.75),v(0,0,10),16).z(),-375,"满刹车应取消驱动并加强减速");
        near(force(body,point,wheel,v(2,0,0),0).x(),-120,"车轮横向摩擦没有抑制侧滑");
        near(PhysicsWheelDynamics.force(body,point,wheel,body.rotation(),body.position().add(v(0,2,0)),PhysicsVector.ZERO,PhysicsVector.ZERO,16).length(),0,"离地轮胎仍在提供支撑或驱动");
        near(force(body,point,wheel(point,0,0),PhysicsVector.ZERO,16).length(),0,"没有装轮胎的轮座产生了地面力");
        PhysicsVector sum=body.gravity().scale(body.mass()),torque=PhysicsVector.ZERO;
        var loads=new ArrayList<PhysicsBody.Load>();
        var tilted=PhysicsBody.Rotation.of(new Quaterniond().rotationZ(.02));
        for(int x:new int[]{-1,1})for(int z:new int[]{-1,1}) {
            var at=v(x,-.5,z);var tire=wheel(at,0,.75);sum=sum.add(force(body,at,tire,PhysicsVector.ZERO,0));
            var local=PhysicsWheelDynamics.force(body,at,tire,tilted,body.position(),PhysicsVector.ZERO,PhysicsVector.ZERO,0);
            torque=torque.add(tilted.world(at).cross(tilted.world(local)));
            loads.add(new PhysicsBody.Load("wheel:"+x+","+z,"offroad:wheel_contact",at,PhysicsVector.ZERO,PhysicsVector.ZERO,
                    PhysicsBody.Frame.BODY,false,0,0,null,tire.atMount(v(x,0,z)).predictAt(0)));
        }
        near(sum.length(),0,"四轮平衡停车不应被报告为持续下落");
        check(torque.z()<0,"侧倾后受压车轮没有产生原生扶正力矩");
        var heavier=body.ballast(10,PhysicsVector.ZERO,new Matrix3d().zero());
        check(force(heavier,point,wheel,PhysicsVector.ZERO,0).y()>25,"加配重后悬挂法向质量仍冻结在旧车体上");
        var car=new PhysicsBody(body.structureId(),body.dimension(),body.tick(),body.mass(),body.center(),body.inertia(),body.rotation(),
                body.position(),body.velocity(),body.angularVelocity(),body.gravity(),loads,List.of());
        var assessment=PhysicsSimulation.assess(car,PhysicsSimulation.Limits.defaults(),Map.of());
        check(assessment.stoppedEquilibrium()&&assessment.restoringStopped()&&assessment.predictedBalanced(),"四轮弹簧在停车及扰动后的稳定支撑未被计入完整预测: "+assessment);
        var measured=new PhysicsBody.Load("native_wheel","offroad:wheel_contact",point,v(0,123,0),PhysicsVector.ZERO,
                PhysicsBody.Frame.BODY,false,0,0,null,wheel);
        near(wrench(vessel(List.of(measured)),body.rotation(),0).force().y(),23,"实测轮胎载荷被模型参数覆盖");
        // 初始车身稍高时会先沉降，但本就稳定；推荐不能为改善这一瞬间的评分而增加无必要的重物。
        var settling=new PhysicsBody(car.structureId(),car.dimension(),car.tick(),car.mass(),car.center(),car.inertia(),car.rotation(),
                car.position().add(v(0,.005,0)),car.velocity(),car.angularVelocity(),car.gravity(),car.loads(),car.unknowns());
        var kept=PhysicsTrim.recommend(settling,List.of(new PhysicsTrim.Ballast("lower","minecraft:iron_block",v(0,-2,0),2,1)),1,Map.of(),PhysicsSimulation.Limits.defaults());
        check(kept.validation().predictedBalanced()&&kept.proposedBallast().isEmpty(),"已通过动态配平的车辆被推荐额外配重");
        // 真实车保持满刹车，在副本中比较松刹车启动和重新刹停；采样对象及默认工况不能被覆盖。
        var brakedLoads=new ArrayList<PhysicsBody.Load>();var brakes=new LinkedHashMap<String,PhysicsWheel.Brakes>();
        for(var load:car.loads()) {
            brakedLoads.add(new PhysicsBody.Load(load.id(),load.group(),load.point(),load.force(),load.torque(),load.frame(),false,0,0,null,
                    wheel(load.point(),1,.75).atMount(load.wheel().mount()).predictAt(16)));
            brakes.put(load.id(),new PhysicsWheel.Brakes(0,1));
        }
        var parked=new PhysicsBody(car.structureId(),car.dimension(),car.tick(),car.mass(),car.center(),car.inertia(),car.rotation(),
                car.position(),car.velocity(),car.angularVelocity(),car.gravity(),brakedLoads,List.of());
        near(wrench(parked,parked.rotation(),1).force().z(),0,"默认分析不应擅自松开实测刹车");
        var scenario=PhysicsWheelScenarios.apply(parked,brakes);
        near(wrench(scenario,scenario.rotation(),1).force().z(),112,"候选运行工况没有松刹车");
        near(wrench(scenario,scenario.rotation(),0).force().length(),0,"停车工况丢失轮胎支撑或没有取消驱动");
        near(brakes.values().iterator().next().at(.5),.5,"启停过渡没有连续改变刹车");
        check(parked.loads().stream().allMatch(l->l.wheel().brake()==1&&l.wheel().referenceBrakes()==null),"试算污染了真实刹车记录");
        var startStop=PhysicsSimulation.assess(scenario,PhysicsSimulation.Limits.defaults(),Map.of());
        var cruise=startStop.trials().stream().filter(t->t.mode().equals("running")&&t.perturbation().equals("none")).findFirst().orElseThrow();
        var stopped=startStop.trials().stream().filter(t->t.mode().equals("stopping")).findFirst().orElseThrow();
        check(cruise.trajectory().getLast().velocity().z()>.1,"停车中的实车无法预览松刹车后的行驶");
        check(stopped.trajectory().getLast().velocity().length()<.01,"停止工况没有回到满刹车并收敛");
        var gson=new Gson();var tire=scenario.loads().getFirst().wheel();
        check(gson.fromJson(gson.toJson(tire),PhysicsWheel.class).equals(tire),"假设工况在结构化回执中丢失");
        try {PhysicsWheelScenarios.apply(parked,Map.of("missing",new PhysicsWheel.Brakes(0,1)));throw new AssertionError("不存在的轮胎被补造");}
        catch(IllegalArgumentException expected){}
    }
    private static PhysicsWheel wheel(PhysicsVector point,double brake,double radius) {
        return new PhysicsWheel("offroad:small_tire",radius,10,0,v(0,0,1),v(1,0,0),1,0,brake,1,
                1.2791666666666668,v(point.x(),0,point.z()),v(0,1,0),null,"contact",true);
    }
    private static PhysicsVector force(PhysicsBody body,PhysicsVector point,PhysicsWheel wheel,PhysicsVector velocity,double rpm) {
        return PhysicsWheelDynamics.force(body,point,wheel,body.rotation(),body.position(),velocity,PhysicsVector.ZERO,rpm);
    }
}
