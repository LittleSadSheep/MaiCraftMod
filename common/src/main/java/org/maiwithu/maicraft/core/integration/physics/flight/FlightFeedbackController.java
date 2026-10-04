package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightSample.Contact.*;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightEnvelope.Kind.*;

/** 每刻闭环调节实际舵机输入；设计、航路和地形观察由调用方提供，不通过改速度或施力实现飞行。 */
public final class FlightFeedbackController {
    public enum Phase { PREFLIGHT, RUNUP, ROTATE, CLIMB, CRUISE, APPROACH, DESCENT, FLARE, ROLLOUT, GO_AROUND, DONE, FAILED }
    private final FlightEnvelope envelope;
    private final List<Map<String,Object>> transitions=new ArrayList<>();
    private Phase phase=Phase.PREFLIGHT;
    private FlightCommand command=FlightCommand.parked();
    private long previousTick=Long.MIN_VALUE,phaseTick=-1;
    private int stableGround,airborneSamples;
    private double departureHeading,departureHeight;
    private boolean cancelled;
    private String detail="等待实际姿态、接地和起飞通道";
    public FlightFeedbackController(FlightEnvelope envelope){this.envelope=envelope;}

    public FlightCommand tick(FlightSample sample,FlightGuidance course,boolean controlsReady) {
        if(terminal()||sample.tick()<=previousTick)return command;
        previousTick=sample.tick();if(phaseTick<0)phaseTick=sample.tick();
        stableGround=sample.contact()==GROUNDED&&sample.speed()<.35?stableGround+1:0;
        airborneSamples=sample.contact()==AIRBORNE?airborneSamples+1:0;
        if(!controlsReady) {detail="等待原生驾驶控制接管";return command=FlightCommand.parked();}
        if(cancelled&&sample.contact()==GROUNDED&&phase!=Phase.ROLLOUT)transition(Phase.ROLLOUT,sample,"取消后在地面刹停");
        else if(cancelled&&phase.ordinal()<Phase.APPROACH.ordinal()&&sample.contact()==AIRBORNE)
            transition(Phase.APPROACH,sample,"取消后使用当前着陆航路收尾");

        // 已在空中接管时直接稳定航向；地面出发必须等接地与实际通道成立，不能因位移就宣称起飞。
        if(phase==Phase.PREFLIGHT) {
            departureHeading=sample.heading();departureHeight=sample.position().y;
            if(airborneSamples>=3)transition(Phase.CRUISE,sample,"接管已在空中的载具");
            else if(stableGround>=8&&course.departureClear()&&course.corridorObserved())
                transition(envelope.kind()==FIXED_WING?Phase.RUNUP:Phase.CLIMB,sample,"驾驶与起飞通道已就绪");
            else return command=FlightCommand.parked();
        }
        if((phase==Phase.RUNUP||phase==Phase.ROTATE)&&sample.contact()==GROUNDED
                &&(!course.departureClear()||!course.corridorObserved()))transition(Phase.ROLLOUT,sample,"起飞通道改变，终止滑跑");
        if(sample.contact()==AIRBORNE&&(!course.corridorObserved()||!course.corridorClear())&&phase!=Phase.GO_AROUND)
            transition(Phase.GO_AROUND,sample,"航路阻挡或未观测，交由已观测的避让航点引导复飞");

        double speed=sample.forwardSpeed(),altitude=course.cruiseAltitude();
        double heading=FlightSample.heading(course.waypoint().subtract(sample.position()));
        double desiredPitch=heightPitch(sample,altitude),power=speedPower(speed,envelope.cruiseSpeed());
        double lift=lift(sample,altitude),desiredBank;
        boolean brake=false;
        switch(phase) {
            case RUNUP -> {
                heading=departureHeading;desiredPitch=0;power=1;
                if(speed>=envelope.takeoffSpeed())transition(Phase.ROTATE,sample,"达到声明的抬轮速度");
                else if(sample.tick()-phaseTick>600)transition(Phase.ROLLOUT,sample,"滑跑未达到抬轮速度");
            }
            case ROTATE -> {
                heading=departureHeading;desiredPitch=envelope.climbPitch();power=1;
                if(airborneSamples>=3&&sample.position().y>departureHeight+1.5)transition(Phase.CLIMB,sample,"持续离地并获得高度");
                else if(sample.tick()-phaseTick>300)transition(sample.contact()==AIRBORNE?Phase.GO_AROUND:Phase.ROLLOUT,
                        sample,"抬轮后未获得足够高度，按实际接地状态复飞或刹停");
            }
            case CLIMB -> {
                power=1;
                if(envelope.kind()==AIRSHIP)desiredPitch=0;
                if(sample.contact()==AIRBORNE&&sample.position().y>=altitude-2)transition(Phase.CRUISE,sample,"已到巡航高度");
            }
            case CRUISE -> {
                if(horizontalDistance(sample.position(),course.approachPoint())<Math.max(24,sample.horizontalSpeed()*3))
                    transition(Phase.APPROACH,sample,"接近进近起点");
            }
            case APPROACH -> {
                heading=FlightSample.heading(course.approachPoint().subtract(sample.position()));
                if(!course.landingSiteObserved())transition(Phase.GO_AROUND,sample,"落点尚无完整观察，继续寻找进近航路");
                else if(envelope.kind()==AIRSHIP||horizontalDistance(sample.position(),course.approachPoint())<12
                        &&Math.abs(FlightSample.wrap(course.landingHeading()-sample.heading()))<Math.toRadians(15))
                    transition(Phase.DESCENT,sample,"进近航向与着陆场地已对齐");
            }
            case DESCENT -> {
                double distance=horizontalDistance(sample.position(),course.touchdown());
                altitude=Math.min(course.cruiseAltitude(),course.touchdown().y+distance*Math.tan(envelope.approachPitch()));
                heading=course.landingHeading();desiredPitch=heightPitch(sample,altitude);
                power=envelope.kind()==AIRSHIP?Math.clamp(distance/20,0,.5):speedPower(speed,envelope.takeoffSpeed()*1.25);
                lift=lift(sample,Math.max(course.touchdown().y,Math.min(altitude,sample.position().y-envelope.descentRate())));
                if(sample.contact()==GROUNDED)transition(Phase.ROLLOUT,sample,"已观察到接地");
                else if(envelope.kind()==FIXED_WING&&sample.position().y-course.touchdown().y<Math.max(2,speed*.15))
                    transition(Phase.FLARE,sample,"近地拉平");
                else if(!course.landingSiteObserved()||envelope.kind()==FIXED_WING
                        &&Math.abs(FlightSample.wrap(course.landingHeading()-sample.heading()))>Math.toRadians(40))
                    transition(Phase.GO_AROUND,sample,"进近偏离或场地变化，复飞");
            }
            case FLARE -> {
                heading=course.landingHeading();desiredPitch=Math.toRadians(5);power=0;
                if(sample.contact()==GROUNDED)transition(Phase.ROLLOUT,sample,"拉平后实际接地");
                else if(!course.landingSiteObserved()||sample.tick()-phaseTick>100)
                    transition(Phase.GO_AROUND,sample,"拉平后未确认接地，复飞");
            }
            case ROLLOUT -> {
                desiredPitch=0;heading=sample.heading();power=0;lift=0;brake=true;
                if(stableGround>=12)transition(cancelled||horizontalDistance(sample.position(),course.touchdown())>16?Phase.FAILED:Phase.DONE,
                        sample,cancelled?"已在地面停止取消的飞行":"实际接地并停止，按与选定落点距离结算");
            }
            case GO_AROUND -> {
                power=1;desiredPitch=envelope.climbPitch();lift=1;
                if(sample.contact()==GROUNDED)transition(Phase.ROLLOUT,sample,"避让期间已接地，先刹停");
                else if(course.corridorObserved()&&course.corridorClear()&&sample.position().y>=altitude-1)
                    transition(Phase.CRUISE,sample,"避让通道和高度恢复");
            }
            default -> { }
        }
        if(terminal())return command=FlightCommand.parked();
        // 能量不足时不继续大幅抬头；姿态率提供阻尼，避免舵面在目标两侧连续过冲。
        if(envelope.kind()==FIXED_WING&&speed<envelope.takeoffSpeed()*.9&&sample.contact()==AIRBORNE)
            desiredPitch=Math.min(desiredPitch,Math.toRadians(-3));
        double headingError=FlightSample.wrap(heading-sample.heading());
        desiredBank=envelope.kind()==AIRSHIP||sample.contact()==GROUNDED?0:Math.clamp(headingError*.65,-envelope.maximumBank(),envelope.maximumBank());
        command=new FlightCommand(power,servo(desiredPitch-sample.pitch(),sample.pitchRate(),3,.8),
                servo(FlightSample.wrap(desiredBank-sample.bank()),sample.bankRate(),3,1),
                servo(headingError,sample.headingRate(),envelope.kind()==AIRSHIP?1.5:.7,.5),lift,brake);
        return command;
    }
    private double heightPitch(FlightSample s,double altitude) {
        double vertical=Math.clamp((altitude-s.position().y)*.25,-envelope.descentRate(),envelope.climbRate());
        return Math.clamp(Math.atan2(vertical,Math.max(1,s.horizontalSpeed()))+(vertical-s.velocity().y)*.025,
                -envelope.approachPitch(),envelope.climbPitch());
    }
    private static double speedPower(double speed,double desired){return Math.clamp(.55+(desired-speed)*.10,0,1);}
    private double lift(FlightSample s,double altitude){return envelope.kind()==AIRSHIP?Math.clamp(.5+(altitude-s.position().y)*.12-s.velocity().y*.2,0,1):0;}
    private static double servo(double error,double rate,double p,double d){double value=error*p-rate*d;return Math.abs(value)<.025?0:Math.clamp(value,-1,1);}
    private static double horizontalDistance(Vec3 a,Vec3 b){return a.subtract(b).horizontalDistance();}
    private void transition(Phase next,FlightSample sample,String reason) {
        if(phase==next)return;
        transitions.add(Map.of("from",phase.name(),"to",next.name(),"game_tick",sample.tick(),"reason",reason));
        phase=next;phaseTick=sample.tick();detail=reason;
    }
    public void requestLanding(){cancelled=true;}
    public Phase phase(){return phase;}
    public boolean terminal(){return phase==Phase.DONE||phase==Phase.FAILED;}
    public boolean succeeded(){return phase==Phase.DONE;}
    public String detail(){return detail;}
    public List<Map<String,Object>> transitions(){return List.copyOf(transitions);}
}
