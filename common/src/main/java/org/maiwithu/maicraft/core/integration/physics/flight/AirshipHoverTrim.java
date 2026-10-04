package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;

/** 用起飞前实际质量与已配置气源的目标升力估算悬停基准，避免所有飞艇都被当成半升力就能悬停。 */
record AirshipHoverTrim(double weight,double fullLift,double fraction,long observedTick,List<String> unknowns) {
    AirshipHoverTrim { unknowns=List.copyOf(unknowns); }
    static AirshipHoverTrim observe(PhysicsBody body) {
        double weight=-body.gravity().y()*body.mass(),lift=0;
        for(var load:body.loads())if(load.group().endsWith(":balloon_lift")) {
            var force=load.frame()==PhysicsBody.Frame.WORLD?load.force():body.rotation().world(load.force());
            lift+=force.y();
        }
        if(!Double.isFinite(weight)||!Double.isFinite(lift)||weight<=0||lift<=0)
            throw new IllegalArgumentException("没有可计算垂直悬停基准的质量、重力或气球升力");
        return new AirshipHoverTrim(weight,lift,Math.clamp(weight/lift,0,1),body.tick(),body.unknowns());
    }
    Map<String,Object> evidence() {
        // 供气动态、气压变化和原生接地仍靠闭环观察；这个基准不是已经达到悬停或通过试飞的证明。
        return Map.of("weight",weight,"configured_full_lift",fullLift,"feedforward",fraction,"observed_tick",observedTick,
                "full_lift_exceeds_weight",fullLift>weight,"source","preflight_native_mass_and_configured_gas_supply",
                "unknowns",unknowns,"flight_verified",false);
    }
}
