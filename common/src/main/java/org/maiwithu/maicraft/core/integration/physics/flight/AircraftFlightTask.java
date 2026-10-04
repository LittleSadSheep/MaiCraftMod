package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.server.FlightStateReader;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.core.task.physics.StructureSeatTask;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 读取本机声明 -> 原生入座 -> 单一飞控租约；配置保存和实际飞到目的地分别结算。 */
public final class AircraftFlightTask extends AbstractCompanionTask<AircraftFlightTaskRecord> {
    private CompletableFuture<AircraftProfile> profileRead;
    private AircraftProfile profile;
    private StructureSeatTask boarding;
    private boolean boarded;
    private Map<String,Object> boardingEvidence=Map.of();
    private AircraftFlightSession session;
    private TransportSession.Result outcome;
    private boolean profileLoaded;
    private FlightStateReader inspection;
    private long inspectionSince=-1;
    private JsonObject inspected=new JsonObject();
    private String inspectionUnknown;
    public AircraftFlightTask(LocalPlayer player,AircraftFlightTaskRecord record){super(player,record);}
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        if(profileRead==null) {
            var identity=StateIdentity.resolve(Minecraft.getInstance()).orElseThrow(()->new IllegalStateException("flight profile world identity unavailable"));
            var store=new AircraftProfileStore(identity);String dimension=player.level().dimension().location().toString();
            // 声明只绑定实际观察到的结构；不存在的编号不能写成已可用飞机。
            if(SableStructureBridge.find(player.clientLevel,r.structureId)==null)return failed("指定物理结构当前不可读");
            profileRead=r.declared==null?store.read(dimension).thenApply(all->all.get(r.structureId)):store.save(dimension,r.structureId,r.declared);
        }
        if(!profileLoaded) {
            if(!profileRead.isDone())return TaskState.RUNNING;
            profile=profileRead.join();profileLoaded=true;
            if(profile==null&&!r.operation.equals("inspect"))return failed("没有这架载具的飞控声明；先提供座位、打字机与 keys 映射");
        }
        if(r.operation.equals("inspect"))return inspect();
        if(!r.operation.equals("fly"))return TaskState.SUCCESS;
        if(outcome!=null) {
            if(outcome.state()==TransportSession.State.SUCCEEDED)return TaskState.SUCCESS;
            return failed(outcome.code()+": "+outcome.detail());
        }
        if(!boarded) {
            if(boarding==null)boarding=new StructureSeatTask(player,new BoardStructureTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),r.structureId,null,profile.seat()));
            var state=runChild(boarding);if(state==null)return TaskState.RUNNING;
            var result=boarding.result(state);boardingEvidence=result.data();boarding=null;
            if(!result.success())return failed(result.message());boarded=true;
        }
        if(session==null) {
            var ship=SableStructureBridge.find(player.clientLevel,r.structureId);
            if(ship==null||ship.pose()==null)return failed("登机后飞机姿态不可读");
            Vec3 destination=r.destination;
            if(destination==null)destination=ship.pose().position().add(direction(r.direction,ship.pose().normalToWorld(profile.forwardVector())).scale(r.distance));
            session=new AircraftFlightSession(r.structureId,profile,destination,r.altitude);
        }
        if(!TransportRuntime.owns(this)&&!TransportRuntime.acquire(this,"aircraft",session,ctx,result->outcome=result))return TaskState.RUNNING;
        TransportRuntime.drive(this,ctx);return TaskState.RUNNING;
    }
    private TaskState inspect() {
        // 没登记键位也能检查实际接地；观测不可用时保留原因，不把声明读取成功冒称飞行或支撑已验证。
        try {
            if(inspection==null){inspection=new FlightStateReader(player,r.structureId,profile==null?null:profile.typewriter());inspectionSince=player.level().getGameTime();}
            inspection.tick(player);inspected=inspection.evidence();
            if(inspected.has("state"))return TaskState.SUCCESS;
            if(player.level().getGameTime()-inspectionSince>200){inspectionUnknown="native flight observation timed out";return TaskState.SUCCESS;}
            return TaskState.RUNNING;
        } catch(RuntimeException unavailable){inspectionUnknown=unavailable.getMessage();return TaskState.SUCCESS;}
    }
    public static Vec3 direction(String name,Vec3 forward) {
        Vec3 horizontal=new Vec3(forward.x,0,forward.z).normalize();
        return switch(name) {
            case "north" -> new Vec3(0,0,-1);case "south" -> new Vec3(0,0,1);
            case "east" -> new Vec3(1,0,0);case "west" -> new Vec3(-1,0,0);
            case "forward" -> horizontal;case "backward" -> horizontal.scale(-1);
            case "right" -> horizontal.cross(new Vec3(0,1,0));case "left" -> new Vec3(0,1,0).cross(horizontal);
            default -> throw new IllegalArgumentException("flight direction must be cardinal or forward/backward/left/right");
        };
    }
    private TaskState failed(String reason){fail(reason,FailureType.UNKNOWN);return TaskState.FAILED;}
    @Override public void stop(LocalPlayer player,Task.StopReason why){
        if(boarding!=null)boarding.stop(player,why);
        TransportRuntime.cancel(this);super.stop(player,why);
    }
    @Override protected void cleanup(){if(inspection!=null)inspection.close();if(boarding!=null){boarding.result(TaskState.CANCELLED);boarding=null;}TransportRuntime.cancel(this);super.cleanup();}
    @Override public Map<String,Object> progress(){return session==null?Map.of("phase",!profileLoaded?"loading_flight_profile":r.operation.equals("inspect")?"inspecting_aircraft_state":"boarding_aircraft"):session.diagnostics();}
    @Override protected Map<String,Object> resultData() {
        var data=new LinkedHashMap<String,Object>();data.put("structure_id",r.structureId.toString());data.put("operation",r.operation);
        if(profile!=null)data.put("flight_profile",profile.json());data.put("boarding",boardingEvidence);
        if(session!=null)data.put("flight",session.evidence());data.put("flight_verified",outcome!=null&&outcome.state()==TransportSession.State.SUCCEEDED);
        data.put("profile_registered",profile!=null);
        if(inspected.size()>0)data.put("native_flight_state",inspected);if(inspectionUnknown!=null)data.put("native_observation_unknown",inspectionUnknown);
        data.put("configuration_is_flight_proof",false);return data;
    }
    @Override protected String successMessage(){return r.operation.equals("fly")?"已在指定载具内完成飞控流程并观察到着陆停稳":"飞控操纵声明已读取或保存，实际飞行尚需独立验证";}
}
