package org.maiwithu.maicraft.server.physics;

import com.google.gson.Gson;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsWheel;

/** 模拟轮胎先算冲量、稍后批量施力；没有应用证据的临时力不能被加入真实整车合力。 */
public final class NativeWheelCaptureTest {
    public static void run() {
        check(NativeWheelParameters.minimumFriction(new LegacyTire())==0,"旧版原生轮胎不应被要求提供尚不存在的最低摩擦字段");
        check(NativeWheelParameters.minimumFriction(new ModernTire())==.8,"新版轮胎自带的最低摩擦被忽略");
        var zero=PhysicsVector.ZERO;var up=new PhysicsVector(0,1,0);
        var wheel=new PhysicsWheel("offroad:small_tire",.75,10,0,new PhysicsVector(0,0,1),new PhysicsVector(1,0,0),
                1,32,0,.6,1,new PhysicsVector(5,70,5),up,null,"contact_pending_application",false);
        var queued=new NativeWheelCapture.Sample(new BlockPos(100,50,100),new PhysicsVector(101.5,50.5,100.5),
                new PhysicsVector(0,1,2),wheel,true,false,List.of());
        BlockPos origin=new BlockPos(100,50,100);
        check(NativeWheelCapture.load(queued,origin,.025).force().equals(zero),"未提交的轮胎冲量被冒充为真实施力");
        var applied=NativeWheelCapture.applied(queued);var load=NativeWheelCapture.load(applied,origin,.025);
        check(load.force().equals(new PhysicsVector(0,40,80)),"未按原生子步把冲量换算为力");
        check(load.point().equals(new PhysicsVector(1.5,.5,.5)),"轮胎作用点没有转换为同一结构原点");
        check(load.wheel().forceApplied()&&load.wheel().contactState().equals("contact"),"批量施力确认没有更新接地证据");
        var idle=new NativeWheelCapture.Sample(origin,zero,zero,wheel,false,false,List.of());
        check(!NativeWheelCapture.applied(idle).applied(),"空的施力回调不能捏造本子步的轮胎作用");
        var gson=new Gson();var restored=gson.fromJson(gson.toJson(load),PhysicsBody.Load.class);
        check(restored.wheel().equals(load.wheel()),"快照分页序列化丢失了轮胎、刹车或支撑平面");
        check(!queued.wheel().forceApplied(),"后续确认污染了此前未提交的观察");
        // 自定义超大轮胎可能在射线未碰到地面时仍有原生施力，不能反向捏造接地命中。
        var missing=gson.toJsonTree(wheel).getAsJsonObject();missing.remove("groundPoint");missing.remove("groundNormal");missing.addProperty("contactState","no_ground");
        var noGround=new NativeWheelCapture.Sample(origin,zero,up,gson.fromJson(missing,PhysicsWheel.class),true,false,List.of());
        check(NativeWheelCapture.applied(noGround).wheel().contactState().equals("no_ground"),"施力确认被误用来捏造地面");
    }
    public static final class LegacyTire {public float radius(){return .75f;}}
    public static final class ModernTire {public double minimumFriction(){return .8;}}
    private static void check(boolean okay,String why) {if(!okay)throw new AssertionError(why);}
}
