package org.maiwithu.maicraft.server.physics;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.network.OptionalServerMixinPlugin;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** 先为可触及的原生组装器建立只读观察，再按玩家与世界绑定查询；实际动作仍由原生玩家包触发。 */
public final class PhysicsAssemblyService {
    private static final String ASSEMBLER="dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity";
    private PhysicsAssemblyService() {}
    public static JsonObject inspect(ServerPlayer player,JsonObject request) {
        try { return read(player,request); }
        catch(ServerOperationException known) { throw known; }
        catch(RuntimeException|LinkageError missing) {
            // 签名不匹配和原生字段缺失是观察未知，不把它包装成“未发现组装结果”。
            throw ServerAccess.denied("assembly_observation_failed",missing.toString());
        }
    }
    private static JsonObject read(ServerPlayer player,JsonObject request) {
        if(request.has("watch_id")) return NativeAssemblyCapture.poll(player,UUID.fromString(ServerAccess.text(request,"watch_id")));
        // 必须证明变换后的原生入口真正接到观察器，不能把 require=0 未命中误报为可确认组装。
        NativeApi.type("dev.simulated_team.simulated.network.packets.AssemblePacket");
        NativeApi.type("dev.simulated_team.simulated.util.SimAssemblyHelper");
        if(!OptionalServerMixinPlugin.assemblyEvents()) throw ServerAccess.denied("assembly_observer_unavailable","原生组装观察钩子未安装");
        BlockPos pos=ServerAccess.position(request.getAsJsonObject("position"));
        if(!player.isAlive()||!player.serverLevel().isLoaded(pos)) throw ServerAccess.denied("unloaded","当前身体或组装器区块不可读");
        var entity=player.serverLevel().getBlockEntity(pos);
        if(!NativeApi.is(entity,ASSEMBLER)) throw ServerAccess.denied("not_physics_assembler","目标不是原生物理组装器");
        Object helper=NativeApi.constant("dev.ryanhcode.sable.Sable","HELPER");
        Object ship=NativeApi.call(helper,null,"getContaining",entity);
        UUID id=null;Vec3 point=pos.getCenter();
        if(ship!=null) {
            id=(UUID)NativeApi.call(ship,null,"getUniqueId");
            Object pose=NativeApi.call(ship,null,"logicalPose");
            var transformed=(Vector3dc)NativeApi.call(pose,null,"transformPosition",new Vector3d(point.x,point.y,point.z));
            point=new Vec3(transformed.x(),transformed.y(),transformed.z());
        }
        if(player.getEyePosition().distanceToSqr(point)>Math.pow(player.blockInteractionRange()+2,2))
            throw ServerAccess.denied("out_of_range","先靠近并看见组装器，再登记原生请求观察");
        return NativeAssemblyCapture.watch(player,pos,entity,id);
    }
}
