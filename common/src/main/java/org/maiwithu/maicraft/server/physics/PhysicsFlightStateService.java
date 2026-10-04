package org.maiwithu.maicraft.server.physics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** 飞控的持续只读遥测只取当前原生采样，不重跑配重搜索、候选编辑或整机气动建模。 */
public final class PhysicsFlightStateService {
    private static final Gson GSON=new Gson();
    private static final String TYPEWRITER="dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity";
    private PhysicsFlightStateService() {}
    public static JsonObject inspect(ServerPlayer player,JsonObject request) {
        UUID id=UUID.fromString(ServerAccess.text(request,"structure_id"));
        Object ship=PhysicsSnapshotService.find(player,id);
        NativePhysicsCapture.watch(ship);PhysicsBody body=NativePhysicsCapture.latest(ship);
        var out=new JsonObject();out.addProperty("structure_id",id.toString());
        out.addProperty("dimension",player.level().dimension().location().toString());
        if(body==null||player.level().getGameTime()-body.tick()>5) {
            out.addProperty("state","sampling");out.addProperty("detail",NativePhysicsCapture.error(ship));return out;
        }
        Object plot=NativeApi.call(ship,null,"getPlot");
        BlockPos origin=(BlockPos)NativeApi.call(plot,null,"getCenterBlock");
        out.addProperty("state","ready");out.addProperty("tick",body.tick());
        out.add("origin_storage",GSON.toJsonTree(new int[]{origin.getX(),origin.getY(),origin.getZ()}));
        out.add("position",GSON.toJsonTree(body.position()));out.add("rotation",GSON.toJsonTree(body.rotation()));
        // 起飞输入后读真实质量和实际作用力，供诊断充气不足或配平误差；不把目标稳态预测当作当刻升力。
        out.addProperty("mass",body.mass());out.add("gravity",GSON.toJsonTree(body.gravity()));
        out.add("actual_loads",GSON.toJsonTree(body.loads()));out.add("load_unknowns",GSON.toJsonTree(body.unknowns()));
        if(request.has("typewriter_position")) {
            BlockPos position=origin.offset(PhysicsBlockEdits.local(request.getAsJsonObject("typewriter_position")));
            var controller=new JsonObject();var entity=player.serverLevel().hasChunkAt(position)?player.serverLevel().getBlockEntity(position):null;
            boolean available=NativeApi.is(entity,TYPEWRITER);controller.addProperty("available",available);
            if(available) {
                // 服务器当前使用者才是控制权事实，客户端右键预测和键包派发不能代替这项确认。
                controller.addProperty("owned_by_player",NativeApi.truth(NativeApi.call(entity,null,"checkUser",player.getUUID())));
                controller.addProperty("in_use",NativeApi.truth(NativeApi.call(entity,null,"isInUse")));
                controller.add("pressed_keys",GSON.toJsonTree(NativeApi.call(entity,null,"getPressedKeys")));
            }
            out.add("typewriter",controller);
        }
        var contact=wheelContact(body);
        // 轮胎真实接地优先；无轮飞艇或轮胎均未支撑时再核对船壳，保留两个来源各自的事实。
        if(!contact.get("state").getAsString().equals("grounded")) {
            JsonObject hull=FlightHullSupport.read(player.serverLevel(),ship);contact.add("hull_support",hull);
            if(hull.get("state").getAsString().equals("grounded")||contact.get("wheel_count").getAsInt()==0
                    &&!contact.get("missing_wheel_observation").getAsBoolean()) {
                contact.addProperty("state",hull.get("state").getAsString());contact.addProperty("source","observed_hull_collision_geometry");
            }
        }
        out.add("native_contact",contact);
        out.addProperty("read_only",true);
        return out;
    }
    static JsonObject wheelContact(PhysicsBody body) {
        int wheels=0,contact=0,knownAirborne=0,unknown=0;
        for(var load:body.loads()) {
            if(!load.group().equals("offroad:wheel_contact"))continue;
            wheels++;
            if(load.wheel()==null){unknown++;continue;}
            var wheel=load.wheel();
            if(wheel.contactState().equals("contact")&&wheel.forceApplied())contact++;
            else if(wheel.radius()>0&&(wheel.contactState().equals("airborne")||wheel.contactState().equals("no_ground")))knownAirborne++;
            else unknown++;
        }
        // 原生车轮旁听缺失时不能把“未读到轮子”解释成飞起来；无轮船体的支撑另需几何或碰撞观察。
        boolean missingHook=body.unknowns().stream().anyMatch(text->text.contains("车轮"));
        var out=new JsonObject();out.addProperty("source","native_wheel_substep");out.addProperty("wheel_count",wheels);
        out.addProperty("contact_count",contact);out.addProperty("airborne_count",knownAirborne);out.addProperty("unknown_count",unknown);
        out.addProperty("missing_wheel_observation",missingHook);
        out.addProperty("state",contact>0?"grounded":wheels>0&&knownAirborne==wheels&&!missingHook?"airborne":"unknown");
        return out;
    }
}
