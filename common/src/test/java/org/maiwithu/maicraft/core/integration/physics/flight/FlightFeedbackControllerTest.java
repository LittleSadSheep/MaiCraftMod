package org.maiwithu.maicraft.core.integration.physics.flight;

import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.server.physics.PhysicsFlightStateServiceTest;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightSample.Contact.*;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightFeedbackController.Phase.*;

/** 回放带独立接地事实的起降轨迹，检查每阶段输入；它验证控制逻辑，不能代替真实飞机的运行验收。 */
public final class FlightFeedbackControllerTest {
    private static final FlightGuidance COURSE=new FlightGuidance(new Vec3(0,100,200),new Vec3(0,100,180),
            new Vec3(0,80,300),0,100,true,true,true,true);
    public static void main(String[] args) throws Exception {
        nativePoseSampling();takeoffAndLanding();unknownGroundCannotFinish();cancelInAirKeepsControl();obstacleRequiresGoAround();keyMixer();groundDrivingIsNotFlight();verticalAirshipDeparture();abortedTakeoffStaysStopped();airshipApproachBeforeDescent();
        PhysicsFlightStateServiceTest.run();
        FlightPathProbeTest.run();
        System.out.println("FlightFeedbackControllerTest: passed");
    }
    private static void nativePoseSampling() {
        var sampler=new FlightSample.Sampler();Vec3 forward=new Vec3(0,0,1);
        check(sampler.observe(1,pose(0,0,0),forward,GROUNDED)==null,"首刻不能捏造零速反馈");
        var s=sampler.observe(3,pose(1,0,0),forward,AIRBORNE);
        near(s.velocity().z,10,"按两刻真实间隔换算世界速度");
        check(sampler.observe(3,pose(4,0,0),forward,AIRBORNE)==null,"同刻重复姿态不重复积分");
        s=sampler.observe(4,pose(1.5,-.1,0),forward,AIRBORNE);
        near(s.pitch(),.1,"负X转动对应机头上仰");
        var bank=new FlightSample.Sampler();bank.observe(1,pose(0,0,0),forward,GROUNDED);
        near(bank.observe(2,pose(0,0,.2),forward,GROUNDED).bank(),.2,"右机翼下降对应正滚转");
        check(sampler.observe(30,pose(20,0,0),forward,AIRBORNE)==null,"长时间断采必须重建基线");
    }
    private static void takeoffAndLanding() {
        var c=new FlightFeedbackController(FlightEnvelope.fixedWing());
        for(int t=1;t<=8;t++)c.tick(sample(t,80,0,0,GROUNDED),COURSE,true);
        check(c.phase()==RUNUP,"连续停稳和起飞通道明确后才滑跑");
        c.tick(sample(9,80,10,4,GROUNDED),COURSE,true);
        check(c.phase()==ROTATE,"达到速度后抬轮");
        for(int t=10;t<=12;t++)c.tick(sample(t,83,12,10,AIRBORNE),COURSE,true);
        check(c.phase()==CLIMB,"持续离地并有高度增量才算起飞");
        c.tick(sample(13,100,16,40,AIRBORNE),COURSE,true);
        check(c.phase()==CRUISE,"爬升达到巡航高度");
        c.tick(sample(14,100,16,160,AIRBORNE),COURSE,true);
        c.tick(sample(15,100,12,179,AIRBORNE),COURSE,true);
        check(c.phase()==DESCENT,"先到进近点并对齐，再沿下滑道下降");
        c.tick(sample(16,81,10,290,AIRBORNE),COURSE,true);
        check(c.phase()==FLARE,"近地拉平保持原生接地观察");
        c.tick(sample(17,80,3,297,GROUNDED),COURSE,true);
        for(int t=18;t<=30;t++)c.tick(sample(t,80,0,299,GROUNDED),COURSE,true);
        check(c.succeeded()&&c.transitions().stream().anyMatch(x->x.get("to").equals("ROLLOUT")),"确认接地并停止才完成着陆");
    }
    private static void unknownGroundCannotFinish() {
        var c=new FlightFeedbackController(FlightEnvelope.fixedWing());
        for(int t=1;t<40;t++)c.tick(sample(t,80,0,300,UNKNOWN),COURSE,true);
        check(c.phase()==PREFLIGHT&&!c.terminal(),"高度和零速度不能代替接地证据");
    }
    private static void cancelInAirKeepsControl() {
        var c=new FlightFeedbackController(FlightEnvelope.fixedWing());
        for(int t=1;t<=3;t++)c.tick(sample(t,100,16,60,AIRBORNE),COURSE,true);
        c.requestLanding();FlightCommand command=c.tick(sample(4,100,16,70,AIRBORNE),COURSE,true);
        check(!c.terminal()&&c.phase()==APPROACH&&!command.brake(),"空中取消应进入进近，不能伪报停车或立即遗弃控制");
    }
    private static void obstacleRequiresGoAround() {
        var c=new FlightFeedbackController(FlightEnvelope.fixedWing());
        for(int t=1;t<=3;t++)c.tick(sample(t,95,16,70,AIRBORNE),COURSE,true);
        var unknown=new FlightGuidance(new Vec3(-20,110,100),COURSE.approachPoint(),COURSE.touchdown(),0,110,true,false,false,false);
        FlightCommand cmd=c.tick(sample(4,95,16,75,AIRBORNE),unknown,true);
        check(c.phase()==GO_AROUND&&cmd.power()<1&&!cmd.brake()&&!c.terminal(),"未观测通道不能被当成巡航成功或继续盲目满动力");
        check(Math.abs(cmd.bank())<=1&&Math.abs(cmd.pitch())<=1,"姿态校正只能给原生输入范围内的指令");
    }
    private static void keyMixer() {
        var m=new FlightKeyMixer(Map.of(FlightKeyMixer.Role.POWER,32,FlightKeyMixer.Role.PITCH_UP,87,
                FlightKeyMixer.Role.PITCH_DOWN,83,FlightKeyMixer.Role.BRAKE,66));
        int powerTicks=0;
        for(int t=1;t<=80;t++) {
            var keys=m.tick(t,new FlightCommand(.25,1,0,0,0,false));
            if(keys.contains(32))powerTicks++;
            check(keys.contains(87)&&!keys.contains(83),"正舵不能同时按住反舵");
            check(keys.equals(m.tick(t,FlightCommand.parked())),"重复采样刻不能二次调制按键");
        }
        check(powerTicks==20,"四分之一动力按可被原生部件处理的保持窗口输出，不能放大成满动力");
        var reverse=m.tick(81,new FlightCommand(0,-1,0,0,0,false));
        check(reverse.contains(83)&&!reverse.contains(87),"改向释放旧轴方向");
        check(m.tick(82,FlightCommand.parked()).equals(Set.of(66)),"停车只保留刹车，不补发旧累计脉冲");
    }
    private static void groundDrivingIsNotFlight() {
        var c=new FlightFeedbackController(FlightEnvelope.airship());
        for(int t=1;t<=640;t++)c.tick(sample(t,80,0,300,GROUNDED),COURSE,true);
        check(!c.succeeded()&&!c.airborneVerified(),"在目标旁边始终接地的车不能冒充完成飞艇起降");
        c=new FlightFeedbackController(FlightEnvelope.fixedWing());
        for(int t=1;t<=3;t++)c.tick(sample(t,100,16,50,AIRBORNE),COURSE,true);
        for(int t=4;t<=20;t++)c.tick(sample(t,80,0,300,GROUNDED),COURSE,true);
        check(c.unexpectedGround()&&!c.succeeded(),"巡航撞地即使碰巧在目的地旁也不能冒充成功着陆");
    }
    private static void verticalAirshipDeparture() {
        var c=new FlightFeedbackController(FlightEnvelope.airship());
        var course=new FlightGuidance(new Vec3(-100,100,200),COURSE.approachPoint(),COURSE.touchdown(),0,100,true,false,false,true);
        FlightCommand command=FlightCommand.parked();
        for(int t=1;t<=8;t++)command=c.tick(sample(t,80,0,0,GROUNDED),course,true);
        check(c.phase()==CLIMB&&command.lift()>0&&command.power()==0&&command.yaw()==0,"飞艇应先升空，不在地面同时开桨或差动倒车");
        command=c.tick(sample(9,83,0,0,AIRBORNE),course,true);
        check(c.phase()==CLIMB&&command.power()==0&&command.yaw()==0,"水平航路暂时未通不能抢走已核对的垂直离地流程");
    }
    private static void abortedTakeoffStaysStopped() {
        // 原生测试中飞艇未持续离地，停机后短暂弹起；此时必须继续收尾，不能误用复飞逻辑再开桨。
        var c=new FlightFeedbackController(FlightEnvelope.airship());
        for(int t=1;t<=609;t++)c.tick(sample(t,80,0,0,GROUNDED),COURSE,true);
        check(c.phase()==ROLLOUT,"无法离地后先进入停机收尾");
        var blocked=new FlightGuidance(COURSE.waypoint(),COURSE.approachPoint(),COURSE.touchdown(),0,100,true,true,false,true);
        var command=c.tick(sample(610,80.3,0,0,AIRBORNE),blocked,true);
        check(c.phase()==ROLLOUT&&command.power()==0&&command.lift()==0,"停机后的短暂离地不能重新启动推进或升力");
    }
    private static void airshipApproachBeforeDescent() {
        // 实测曾在落点外二十格开始下降；飞艇要先飞到场地上方，不能套用固定翼跑道的朝向和下滑顺序。
        var c=new FlightFeedbackController(FlightEnvelope.airship());
        var course=new FlightGuidance(new Vec3(0,100,300),new Vec3(0,100,300),new Vec3(0,80,300),
                Math.PI/2,100,true,true,true,true);
        for(int t=1;t<=3;t++)c.tick(sample(t,100,4,280,AIRBORNE),course,true);
        c.tick(sample(4,100,4,282,AIRBORNE),course,true);
        check(c.phase()==APPROACH,"尚距落点十八格时继续水平进近");
        var command=c.tick(sample(5,100,2,299,AIRBORNE),course,true);
        check(c.phase()==APPROACH&&command.power()==0,"飞到附近但尚未减速时先收推进");
        c.tick(sample(6,100,.2,299.5,AIRBORNE),course,true);
        check(c.phase()==DESCENT,"落点上方且水平速度低后才下降");
        command=c.tick(sample(7,99,.2,299.5,AIRBORNE),course,true);
        check(command.power()==0&&command.yaw()==0,"垂直下降不为了矩形场地朝向再转九十度");
    }
    private static FlightSample sample(long tick,double y,double speed,double z,FlightSample.Contact contact) {
        return new FlightSample(tick,new Vec3(0,y,z),new Vec3(0,0,speed),0,0,0,0,0,0,contact);
    }
    private static StructurePose pose(double z,double pitch,double bank) {
        // 分别绕X或Z测试真实四元数方向，避免测试与控制器共用同一组欧拉角公式。
        return new StructurePose(new Vec3(0,80,z),Math.sin(pitch/2),0,Math.sin(bank/2),Math.cos((pitch+bank)/2),Vec3.ZERO,new Vec3(1,1,1));
    }
    private static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-6,why+": "+actual);}
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
}
