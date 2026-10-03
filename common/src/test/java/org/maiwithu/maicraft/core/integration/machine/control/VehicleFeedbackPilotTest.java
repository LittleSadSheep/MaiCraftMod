package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.List;
import net.minecraft.world.phys.Vec3;

public final class VehicleFeedbackPilotTest {
    public static void main(String[] args) {
        var drive=new VehicleControlPlan.Input("unfamiliar_control",ControlCircuit.Kind.THROTTLE,15,0,15,List.of("wheel"),true);
        var steer=new VehicleControlPlan.Input("unfamiliar_steering",ControlCircuit.Kind.STEERING_WHEEL,0,-180,180,List.of("joint"),false);
        var plan=new VehicleControlPlan(List.of(drive,steer),List.of());
        run(plan,new Vec3(0,0,15),false);
        run(plan,new Vec3(12,0,12),false);
        run(plan,new Vec3(0,0,40),true);
        var blocked=new VehicleFeedbackPilot(plan,new Vec3(10,0,0));
        for(int tick=0;tick<1000 && !blocked.terminal();tick++) blocked.observe(tick,new VehicleFeedbackPilot.Sample(Vec3.ZERO,0),true);
        check(blocked.terminal()&&!blocked.succeeded(),"an immobile assembly must not be reported as driven");
        check(blocked.command().equals(plan.neutral()),"failed probes leave controls neutral");
        var pending=new VehicleFeedbackPilot(plan,new Vec3(0,0,10));
        for(int tick=0;tick<60;tick++) pending.observe(tick,new VehicleFeedbackPilot.Sample(Vec3.ZERO,0),false);
        check(pending.phase()==VehicleFeedbackPilot.Phase.BASELINE,"unconfirmed inputs cannot advance calibration");
        // 重车在轻微松刹车时几乎不动，允许下一档测出响应，但不能在测出低档响应后继续试探全油门。
        var weak=new VehicleFeedbackPilot(new VehicleControlPlan(List.of(drive),List.of()),new Vec3(0,0,6));
        Vec3 weakAt=Vec3.ZERO;double minimumSignal=15;
        for(int tick=0;tick<1000&&!weak.terminal();tick++) {
            double signal=weak.command().get("unfamiliar_control");minimumSignal=Math.min(minimumSignal,signal);
            weakAt=weakAt.add(0,0,Math.max(0,13-signal)*.025);
            weak.observe(tick,new VehicleFeedbackPilot.Sample(weakAt,0),true);
        }
        check(weak.succeeded()&&minimumSignal==11,"weak initial response must escalate once, then retain the proven low-power drive");
        check(weak.command().get("unfamiliar_control")==15,"arrival must restore persistent full brake");
        // 两侧的小方向盘档位不足时继续校准；各找到一个有效档位后停止增加，取消时仍回正并保持刹车。
        var weakSteering=new VehicleFeedbackPilot(plan,new Vec3(10,0,30));Vec3 steeringAt=Vec3.ZERO;double steeringYaw=0,largest=0;int steeringTick=0;
        for(;steeringTick<1500&&!weakSteering.terminal()&&weakSteering.phase()!=VehicleFeedbackPilot.Phase.DRIVE;steeringTick++) {
            var command=weakSteering.command();double angle=command.get("unfamiliar_steering");largest=Math.max(largest,Math.abs(angle));
            double velocity=(15-command.get("unfamiliar_control"))*.01;
            steeringYaw+=angle*.00002*(velocity>0?1:0);steeringAt=steeringAt.add(Math.sin(steeringYaw)*velocity,0,Math.cos(steeringYaw)*velocity);
            weakSteering.observe(steeringTick,new VehicleFeedbackPilot.Sample(steeringAt,steeringYaw),true);
        }
        check(weakSteering.phase()==VehicleFeedbackPilot.Phase.DRIVE&&largest==30,"弱转向应在两侧有效档位停止升级，而非试到最大角度");
        weakSteering.cancel();
        for(int i=0;i<20&&!weakSteering.terminal();i++)weakSteering.observe(steeringTick+i,new VehicleFeedbackPilot.Sample(steeringAt,steeringYaw),true);
        check(weakSteering.terminal()&&!weakSteering.succeeded()&&weakSteering.command().equals(plan.neutral()),"校准后取消仍应回正、刹车且不冒称抵达");
        // 低速车的最大每刻转角可能仍小于快速行驶阈值；方向稳定且单位距离转角明确时，驾驶阶段仍应使用它。
        var creeping=new VehicleFeedbackPilot(plan,new Vec3(12,0,12));Vec3 creepAt=Vec3.ZERO;double creepYaw=0;
        for(int tick=0;tick<2000&&!creeping.terminal()&&creeping.phase()!=VehicleFeedbackPilot.Phase.DRIVE;tick++) {
            var command=creeping.command();double velocity=(15-command.get("unfamiliar_control"))*.003;
            creepYaw+=command.get("unfamiliar_steering")*.000001*(velocity>0?1:0);
            creepAt=creepAt.add(Math.sin(creepYaw)*velocity,0,Math.cos(creepYaw)*velocity);
            creeping.observe(tick,new VehicleFeedbackPilot.Sample(creepAt,creepYaw),true);
        }
        check(creeping.phase()==VehicleFeedbackPilot.Phase.DRIVE&&creeping.command().get("unfamiliar_steering")==180,
                "实际缓慢转向不能被忽略成只会直行；应使用已测试的有效方向");
        var spinning=new VehicleFeedbackPilot(plan,new Vec3(0,0,1));
        for(int tick=0;tick<500&&!spinning.terminal();tick++) spinning.observe(tick,new VehicleFeedbackPilot.Sample(Vec3.ZERO,tick*.05),true);
        check(spinning.terminal()&&!spinning.succeeded(),"a rotating hull is not a stopped arrival");
        System.out.println("VehicleFeedbackPilotTest: passed");
    }
    private static void run(VehicleControlPlan plan,Vec3 target,boolean cancel) {
        var pilot=new VehicleFeedbackPilot(plan,target); Vec3 at=Vec3.ZERO; double yaw=0;
        for(int tick=0;tick<2500&&!pilot.terminal();tick++) {
            var command=pilot.command();
            double speed=(15-command.get("unfamiliar_control"))*.04;
            yaw+=command.get("unfamiliar_steering")*.0015*(speed>0?1:0);
            at=at.add(Math.sin(yaw)*speed,0,Math.cos(yaw)*speed);
            pilot.observe(tick,new VehicleFeedbackPilot.Sample(at,yaw),true);
            if(cancel&&tick==130) pilot.cancel();
        }
        check(pilot.terminal(),"feedback controller did not terminate: "+pilot.diagnostics());
        if(cancel) check(!pilot.succeeded()&&pilot.command().equals(plan.neutral()),"cancellation must stop and never report arrival");
        else check(pilot.succeeded()&&at.distanceTo(target)<=3.1,"observed arrival failed: "+at+" "+pilot.diagnostics());
    }
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
}
