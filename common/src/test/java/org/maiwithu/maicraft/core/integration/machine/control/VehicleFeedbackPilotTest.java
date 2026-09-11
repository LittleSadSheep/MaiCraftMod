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
