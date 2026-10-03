package org.maiwithu.maicraft.core.task.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.core.task.physics.PhysicalAssemblyParameters.Adhesive;

/** 读取原生胶层、组装器拒绝原因与玩家协议载荷；这里不创建实体、不调用服务端组装方法。 */
final class NativeAssemblyApi {
    static final String ASSEMBLER="dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity";
    record Bond(UUID id,Adhesive adhesive,AABB bounds) {
        Map<String,Object> evidence() { return Map.of("entity_id",id.toString(),"adhesive",adhesive.item,
                "bounds",List.of(bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ)); }
    }
    private NativeAssemblyApi() {}
    static boolean assembler(BlockEntity entity) { return ControlReflection.is(entity,ASSEMBLER); }
    static String failure(BlockEntity entity) {
        if(!assembler(entity)) return "";
        Object error=ControlReflection.call(entity,"getLastAssemblyException");
        return failureMessage(error);
    }
    static String failureMessage(Object error) {
        if(error==null) return "";
        // Create 的 AssemblyException 通过 component 字段保存服务器拒绝原因，没有 getComponent 方法。
        Object message=ControlReflection.field(error,"component");
        return message instanceof Component component?component.getString():String.valueOf(message);
    }
    static List<Bond> bonds(ClientLevel level,AABB region) {
        var result=new ArrayList<Bond>();
        // 原生查询只返回当前已同步实体，记录完整相交胶层；不能用可视选框代替服务器生成的胶实体。
        for(Entity entity:level.getEntities((Entity)null,region.inflate(.001),NativeAssemblyApi::glue)) {
            for(var adhesive:Adhesive.values()) if(ControlReflection.is(entity,adhesive.entityType))
                result.add(new Bond(entity.getUUID(),adhesive,entity.getBoundingBox()));
        }
        return List.copyOf(result);
    }
    private static boolean glue(Entity entity) {
        if(entity.isRemoved()) return false;
        for(var adhesive:Adhesive.values()) if(ControlReflection.is(entity,adhesive.entityType)) return true;
        return false;
    }
    static boolean covers(Bond bond,Adhesive adhesive,AABB region) {
        return bond.adhesive()==adhesive&&bond.bounds().minX<=region.minX&&bond.bounds().minY<=region.minY
                &&bond.bounds().minZ<=region.minZ&&bond.bounds().maxX>=region.maxX&&bond.bounds().maxY>=region.maxY&&bond.bounds().maxZ>=region.maxZ;
    }
    static List<Bond> newBonds(List<Bond> before,List<Bond> now,Adhesive adhesive,AABB region) {
        // 已存在或只在旁边的胶层不是本次粘接的成功证据；新实体须覆盖作者指定的整块选区。
        return now.stream().filter(b->covers(b,adhesive,region)&&before.stream().noneMatch(old->old.id().equals(b.id()))).toList();
    }
    static CustomPacketPayload bondPacket(Adhesive adhesive,BlockPos first,BlockPos second) {
        return (CustomPacketPayload)ControlReflection.construct(adhesive.packetType,first,second);
    }
    static CustomPacketPayload assemblyPacket(BlockPos assembler) {
        // 这是玩家拉完组装器拉杆后发送的原生请求；不是只控制客户端动画的 FlickAndHoldLever 包。
        return (CustomPacketPayload)ControlReflection.construct("dev.simulated_team.simulated.network.packets.AssemblePacket",assembler);
    }
}
