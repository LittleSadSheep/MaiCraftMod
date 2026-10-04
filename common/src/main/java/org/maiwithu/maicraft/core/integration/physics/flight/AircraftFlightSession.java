package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.server.FlightStateReader;
import org.maiwithu.maicraft.core.integration.machine.control.DriverStation;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;

/** 登机后的整个飞行归属一个交通租约；每刻观察和调舵，LLM 不参与高频按键循环。 */
public final class AircraftFlightSession implements TransportSession {
    private final UUID structureId;
    private final AircraftProfile profile;
    private final Vec3 destination;
    private final Double altitude;
    private final FlightFeedbackController controller;
    private final FlightKeyMixer mixer;
    private final FlightSample.Sampler sampler=new FlightSample.Sampler();
    // 所有真实飞行共用探索记录，普通长途旅行也能留下可供下一次 travel 使用的群系地点。
    private final FlightExplorationRecorder exploration=new FlightExplorationRecorder();
    private final List<Map<String,Object>> samples=new ArrayList<>();
    private AircraftKeyboardControl keyboard;
    private FlightStateReader reader;
    private FlightRoutePlanner route;
    private FlightSample last;
    private FlightGuidance guidance;
    private Result terminal;
    private long started=-1,lastProgress=Long.MIN_VALUE,lastRecorded=Long.MIN_VALUE;
    private double bestDistance=Double.POSITIVE_INFINITY;
    private boolean stopping;
    private boolean stoppingRoute;
    private long parkingSince=-1;
    private int parkedSamples;
    private boolean serverControlConfirmed;
    public AircraftFlightSession(UUID structureId,AircraftProfile profile,Vec3 destination,Double altitude) {
        this.structureId=structureId;this.profile=profile;this.destination=destination;this.altitude=altitude;
        controller=new FlightFeedbackController(profile.envelope());mixer=new FlightKeyMixer(profile.keys());
    }
    @Override public Result tick(LocalPlayerContext ctx) {
        if(terminal!=null)return terminal;
        if(started<0)started=ctx.tickRevision();
        var ship=SableStructureBridge.find(ctx.level(),structureId);
        if(ship==null||ship.pose()==null||ship.plotCenter()==null)return finish(false,"flight_structure_lost","飞机身份或姿态不可读");
        BlockPos seat=ship.plotCenter().offset(profile.seat()),typewriter=ship.plotCenter().offset(profile.typewriter());
        if(!new DriverStation(seat,List.of(typewriter)).seated(ctx.player()))return finish(false,"flight_seat_lost","驾驶员已离开本机座椅");
        try {
            if(reader==null)reader=new FlightStateReader(ctx.player(),structureId,profile.typewriter());
            if(keyboard==null)keyboard=new AircraftKeyboardControl(seat,typewriter,mixer.configuredKeys());
            var contact=reader.tick(ctx.player());
            FlightSample sample=sampler.observe(ctx.level().getGameTime(),ship.pose(),profile.forwardVector(),contact);
            if(sample==null)return Result.running("sampling_aircraft_pose");
            last=sample;
            exploration.tick(ctx.player());
            if(parkingSince>=0) {
                // 松开所有原生按键后还要停稳，防止把依赖一直按刹车的瞬时停车冒充已结束驾驶。
                boolean parked=sample.contact()==FlightSample.Contact.GROUNDED&&sample.speed()<.35
                        &&Math.abs(sample.bank())<Math.toRadians(8)&&Math.abs(sample.pitch())<Math.toRadians(8)&&reader.controllerReleased();
                parkedSamples=parked?parkedSamples+1:0;
                if(parkedSamples>=20)return finish(controller.succeeded(),controller.succeeded()?"flight_landed":"flight_stopped",
                        "释放控制后仍保持接地、姿态稳定与停稳；"+controller.detail());
                if(sample.tick()-parkingSince>100)return finish(false,"flight_parking_unverified","已接地但释放控制后未保持稳定停车");
                return Result.running("verifying_released_parking");
            }
            if(route==null)route=new FlightRoutePlanner(profile.envelope(),destination,altitude==null?Math.max(sample.position().y,destination.y)+32:altitude);
            if(stopping&&!stoppingRoute) {
                // 取消后的落点围绕当前所在地重新找，不能继续为旧远程目的地飞完整段路才收尾。
                route=new FlightRoutePlanner(profile.envelope(),sample.position(),sample.position().y);
                stoppingRoute=true;
            }
            guidance=route.tick(ctx.level(),ship,sample,controller.phase());
            // 先取得原生键盘租约；在认领确认前只请求停车键，不允许预先开启动力。
            if(!keyboard.connected()) {
                keyboard.apply(ctx,ship,mixer.tick(sample.tick(),FlightCommand.parked()));
                if(ctx.tickRevision()-started>200)return finish(false,"flight_controls_unavailable","未能在座椅上接管打字机");
                return Result.running("connecting_aircraft_controls");
            }
            if(!reader.controllerOwned()) {
                if(serverControlConfirmed||ctx.tickRevision()-started>200)return finish(false,"flight_native_owner_lost","服务器未确认本玩家持有打字机控制权");
                return Result.running("confirming_server_control_owner");
            }
            serverControlConfirmed=true;
            if(stopping)controller.requestLanding();
            FlightCommand command=controller.tick(sample,guidance,true);
            keyboard.apply(ctx,ship,mixer.tick(sample.tick(),command));
            if(controller.phase()==FlightFeedbackController.Phase.PREFLIGHT&&ctx.tickRevision()-started>200)
                return finish(false,"flight_departure_unavailable","起飞等待未取得接地、稳定或完整起飞通道证据");
            if(lastRecorded==Long.MIN_VALUE||sample.tick()-lastRecorded>=10||controller.terminal()) {
                lastRecorded=sample.tick();samples.add(Map.of("sample",sample,"command",command,"phase",controller.phase().name()));
            }
            double distance=sample.position().subtract(destination).horizontalDistance();
            if(distance<bestDistance-.5){bestDistance=distance;lastProgress=ctx.tickRevision();}
            if(controller.terminal()) {
                if(!controller.succeeded()&&(sample.contact()!=FlightSample.Contact.GROUNDED||sample.speed()>=.35))
                    return finish(false,"flight_stopped",controller.detail());
                keyboard.close();parkingSince=sample.tick();return Result.running("verifying_released_parking");
            }
            if(ctx.tickRevision()-started>24_000){stopping=true;controller.requestLanding();}
            return Result.running(phase());
        } catch(RuntimeException|LinkageError unavailable) {
            return finish(false,"flight_control_unavailable",unavailable.getMessage()==null?unavailable.toString():unavailable.getMessage());
        }
    }
    private Result finish(boolean success,String code,String detail) {
        boolean effects=keyboard!=null&&keyboard.effectsStarted();
        if(keyboard!=null)keyboard.close();if(reader!=null)reader.close();
        exploration.close();
        boolean uncertain=keyboard!=null&&keyboard.uncertain();
        return terminal=success&&!uncertain?Result.success("确认同艇驾驶、实际接地和停稳；后续步行仍需检查原旅行终点")
                :Result.failed(code,detail,effects,uncertain||last==null||last.contact()!=FlightSample.Contact.GROUNDED);
    }
    @Override public void requestStop(){stopping=true;controller.requestLanding();}
    @Override public void abandon(){finish(false,"flight_control_transferred","人工接管或身体控制权改变，释放本次原生按键");}
    @Override public boolean safeToInterrupt(){
        // 刚发出起飞输入时速度可能仍是零；只有尚未接管按键或完成实际收尾才能直接换任务。
        return terminal!=null||last!=null&&last.contact()==FlightSample.Contact.GROUNDED&&last.speed()<.35
                &&(keyboard==null||!keyboard.effectsStarted());
    }
    @Override public boolean livenessActive(){return terminal==null;}
    /** 取消回执仍需区分尚未接管与已发出输入，不能因着陆收尾尚未结束就丢掉实际效果。 */
    public boolean effectsStarted(){return keyboard!=null&&keyboard.effectsStarted();}
    @Override public long lastVerifiedProgressTick(){return lastProgress;}
    @Override public String phase(){return parkingSince>=0?"verifying_released_parking":controller.phase().name().toLowerCase(Locale.ROOT);}
    @Override public Map<String,Object> diagnostics() {
        var result=new LinkedHashMap<String,Object>();result.put("phase",phase());result.put("structure_id",structureId.toString());
        result.put("destination",destination);result.put("detail",controller.detail());result.put("stopping",stopping);
        result.put("airborne_verified",controller.airborneVerified());result.put("unexpected_ground_contact",controller.unexpectedGround());
        if(last!=null)result.put("actual_flight_state",last);
        if(reader!=null)result.put("native_contact_evidence",reader.evidence());
        if(route!=null)result.put("route",route.evidence());
        if(keyboard!=null)result.put("keyboard",keyboard.evidence());
        result.put("phase_transitions",controller.transitions());return result;
    }
    public Map<String,Object> evidence(){
        var result=new LinkedHashMap<>(diagnostics());result.put("flight_samples",List.copyOf(samples));
        // 返回同一次飞行的跑图查询入口；观察记录与到达／着陆证据保持各自的事实范围。
        result.put("exploration_memory",exploration.evidence());return result;
    }
}
