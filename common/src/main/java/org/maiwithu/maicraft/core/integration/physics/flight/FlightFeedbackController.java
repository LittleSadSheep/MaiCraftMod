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
    private boolean airborneVerified,unexpectedGround;
    private String detail="等待实际姿态、接地和起飞通道";
    public FlightFeedbackController(FlightEnvelope envelope){this.envelope=envelope;}

    public FlightCommand tick(FlightSample sample,FlightGuidance course,boolean controlsReady) {
        if(terminal()||sample.tick()<=previousTick)return command;
        previousTick=sample.tick();if(phaseTick<0)phaseTick=sample.tick();
        stableGround=sample.contact()==GROUNDED&&sample.speed()<.35?stableGround+1:0;
        airborneSamples=sample.contact()==AIRBORNE?airborneSamples+1:0;
        if(airborneSamples>=3&&phase!=Phase.PREFLIGHT&&sample.position().y>departureHeight+1.5)airborneVerified=true;
        if(!controlsReady) {detail="等待原生驾驶控制接管";return command=FlightCommand.parked();}
        if(cancelled&&sample.contact()==GROUNDED&&phase!=Phase.ROLLOUT)transition(Phase.ROLLOUT,sample,"取消后在地面刹停");
        else if(cancelled&&phase.ordinal()<Phase.APPROACH.ordinal()&&sample.contact()==AIRBORNE)
            transition(Phase.APPROACH,sample,"取消后使用当前着陆航路收尾");

        // 已在空中接管时直接稳定航向；地面出发必须等接地与实际通道成立，不能因位移就宣称起飞。
        if(phase==Phase.PREFLIGHT) {
            departureHeading=sample.heading();departureHeight=sample.position().y;
            if(airborneSamples>=3){airborneVerified=true;transition(Phase.CRUISE,sample,"接管已在空中的载具");}
            else if(stableGround>=8&&course.departureClear())
                transition(envelope.kind()==FIXED_WING?Phase.RUNUP:Phase.CLIMB,sample,"驾驶与起飞通道已就绪");
            else return command=FlightCommand.parked();
        }
        if((phase==Phase.RUNUP||phase==Phase.ROTATE)&&sample.contact()==GROUNDED
                &&(!course.departureClear()||!course.corridorObserved()))transition(Phase.ROLLOUT,sample,"起飞通道改变，终止滑跑");
        // 巡航时撞上地形与按进近流程落地不是同一件事；先停机刹车，最终回执保留异常接地。
        if(airborneVerified&&sample.contact()==GROUNDED&&phase!=Phase.DESCENT&&phase!=Phase.FLARE&&phase!=Phase.ROLLOUT) {
            unexpectedGround=true;transition(Phase.ROLLOUT,sample,"非着陆阶段提前接地，停止当前飞行");
        }
        boolean verticalDeparture=envelope.kind()==AIRSHIP&&phase==Phase.CLIMB
                &&(!airborneVerified||sample.position().y<departureHeight+8);
        // 已进入停机收尾后，船壳短暂弹起仍继续刹停，不能因地面旁的航路阻挡再次开动力。
        if(sample.contact()==AIRBORNE&&!verticalDeparture&&(!course.corridorObserved()||!course.corridorClear())
                &&phase!=Phase.GO_AROUND&&phase!=Phase.ROLLOUT)
            transition(Phase.GO_AROUND,sample,"航路阻挡或未观测，交由已观测的避让航点引导复飞");

        double speed=sample.forwardSpeed(),altitude=course.cruiseAltitude();
        double heading=FlightSample.heading(course.waypoint().subtract(sample.position()));
        double desiredPitch=heightPitch(sample,course.commandAltitude()),power=speedPower(speed,envelope.cruiseSpeed());
        double lift=lift(sample,course.commandAltitude()),desiredBank;
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
                power=Math.clamp(speedPower(speed,envelope.cruiseSpeed())+.2,0,1);
                if(envelope.kind()==AIRSHIP) {
                    desiredPitch=0;
                    // 飞艇先在已检查的垂直空间离地，再开推进和偏航，避免贴地旋转的桨叶将吊舱掀翻。
                    if(verticalDeparture){power=0;lift=Math.clamp(.7+(envelope.climbRate()-sample.velocity().y)*.25,0,1);}
                }
                if(sample.contact()==AIRBORNE&&sample.position().y>=altitude-2)transition(Phase.CRUISE,sample,"已到巡航高度");
                else if(airborneVerified&&sample.contact()==AIRBORNE&&course.landingSiteObserved()&&horizontalDistance(sample.position(),course.approachPoint())<24)
                    transition(Phase.APPROACH,sample,"避障高度下已到进近区域");
                else if(!airborneVerified&&sample.tick()-phaseTick>600)transition(Phase.ROLLOUT,sample,"起飞输入后没有确认持续离地");
            }
            case CRUISE -> {
                if(horizontalDistance(sample.position(),course.approachPoint())<Math.max(24,sample.horizontalSpeed()*3))
                    transition(Phase.APPROACH,sample,"接近进近起点");
            }
            case APPROACH -> {
                heading=FlightSample.heading(course.approachPoint().subtract(sample.position()));
                if(!course.landingSiteObserved())transition(Phase.GO_AROUND,sample,"落点尚无完整观察，继续寻找进近航路");
                else if(envelope.kind()==AIRSHIP) {
                    // 飞艇先保持高度移到实际落点上方并减速，不能一进入二十四格进近区就沿跑道朝向盲目下降。
                    double distance=horizontalDistance(sample.position(),course.touchdown());
                    heading=FlightSample.heading(course.touchdown().subtract(sample.position()));
                    lift=lift(sample,course.cruiseAltitude());desiredPitch=0;
                    power=distance>Math.max(1.5,sample.horizontalSpeed()*2)
                            &&Math.abs(FlightSample.wrap(heading-sample.heading()))<Math.toRadians(30)
                            ?Math.clamp((distance-1.5)*.08,0,.6):0;
                    if(distance<=1&&sample.horizontalSpeed()<.3)
                        transition(Phase.DESCENT,sample,"飞艇已在实际落点上方减速，开始垂直下降");
                }
                else if(horizontalDistance(sample.position(),course.approachPoint())<12
                        &&Math.abs(FlightSample.wrap(course.landingHeading()-sample.heading()))<Math.toRadians(15))
                    transition(Phase.DESCENT,sample,"进近航向与着陆场地已对齐");
            }
            case DESCENT -> {
                double distance=horizontalDistance(sample.position(),course.touchdown());
                altitude=Math.min(course.cruiseAltitude(),course.touchdown().y+distance*Math.tan(envelope.approachPitch()));
                heading=course.landingHeading();desiredPitch=heightPitch(sample,altitude);
                power=envelope.kind()==AIRSHIP?Math.clamp(distance/20,0,.5):speedPower(speed,envelope.takeoffSpeed()*1.25);
                lift=lift(sample,Math.max(course.touchdown().y,Math.min(altitude,sample.position().y-envelope.descentRate())));
                if(envelope.kind()==AIRSHIP&&distance<8){power=0;heading=sample.heading();}
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
                if(stableGround>=12)transition(cancelled||!airborneVerified||unexpectedGround||horizontalDistance(sample.position(),course.touchdown())>16?Phase.FAILED:Phase.DONE,
                        sample,cancelled?"已在地面停止取消的飞行":"实际接地并停止，按与选定落点距离结算");
            }
            case GO_AROUND -> {
                // 使用航路层真正检查过的爬升或平飞空间，不能在顶棚下仍盲目拉满抬头。
                power=course.corridorClear()?Math.clamp(speedPower(speed,envelope.cruiseSpeed())+.2,0,1):speedPower(speed,envelope.takeoffSpeed()*1.3);
                desiredPitch=heightPitch(sample,course.commandAltitude());lift=lift(sample,course.commandAltitude());
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
        boolean verticalLanding=envelope.kind()==AIRSHIP&&phase==Phase.DESCENT
                &&horizontalDistance(sample.position(),course.touchdown())<8&&sample.position().y-course.touchdown().y<2;
        command=new FlightCommand(power,servo(desiredPitch-sample.pitch(),sample.pitchRate(),3,.8),
                servo(FlightSample.wrap(desiredBank-sample.bank()),sample.bankRate(),3,1),
                verticalDeparture||verticalLanding?0:servo(headingError,sample.headingRate(),envelope.kind()==AIRSHIP?1.5:.7,.5),lift,brake);
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
    public boolean airborneVerified(){return airborneVerified;}
    public boolean unexpectedGround(){return unexpectedGround;}
    public String detail(){return detail;}
    public List<Map<String,Object>> transitions(){return List.copyOf(transitions);}
}
