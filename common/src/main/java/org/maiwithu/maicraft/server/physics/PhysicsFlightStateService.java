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
        out.add("native_contact",wheelContact(body));
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
