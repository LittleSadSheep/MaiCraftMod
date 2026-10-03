package org.maiwithu.maicraft.server.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只旁听玩家原生组装请求和方块搬移结果；记录身份与坐标转换，不发起组装、不改变物理过程。 */
public final class NativeAssemblyCapture {
    private static final Logger LOG=LoggerFactory.getLogger("maicraft.physics.assembly");
    private static final Map<UUID,Watch> WATCHES=new LinkedHashMap<>();
    private static final ThreadLocal<Watch> ACTIVE=new ThreadLocal<>();
    private static final int LEASE_TICKS=1200;
    static final class Watch {
        final UUID id=UUID.randomUUID(),owner,beforeStructure;
        final WeakReference<ServerLevel> world;
        final WeakReference<BlockEntity> assembler;
        final BlockPos position;
        final Object beforeFailure;
        final JsonObject result=new JsonObject();
        boolean invoked,complete;
        long until;
        Watch(ServerPlayer player,BlockPos position,BlockEntity assembler,UUID beforeStructure) {
            owner=player.getUUID();world=new WeakReference<>(player.serverLevel());this.position=position.immutable();
            this.assembler=new WeakReference<>(assembler);this.beforeStructure=beforeStructure;
            beforeFailure=NativeApi.call(assembler,null,"getLastAssemblyException");until=player.level().getGameTime()+LEASE_TICKS;
        }
        boolean expired() { ServerLevel level=world.get();return level==null||level.getGameTime()>until; }
    }
    private NativeAssemblyCapture() {}
    static JsonObject watch(ServerPlayer player,BlockPos pos,BlockEntity assembler,UUID before) {
        WATCHES.values().removeIf(Watch::expired);
        // 一次尚未结算的同位置观察继续复用，避免暂停/恢复时用新观察覆盖已经提交的原生操作。
        for(var old:WATCHES.values()) if(!old.complete&&old.owner.equals(player.getUUID())
                &&old.world.get()==player.serverLevel()&&old.position.equals(pos)) return snapshot(old);
        if(WATCHES.size()>=128) throw ServerAccess.denied("assembly_observation_capacity","原生组装观察尚未结清，不能覆盖待确认回执");
        var watch=new Watch(player,pos,assembler,before);WATCHES.put(watch.id,watch);return snapshot(watch);
    }
    static JsonObject poll(ServerPlayer player,UUID id) {
        var watch=WATCHES.get(id);
        if(watch==null||!watch.owner.equals(player.getUUID())||watch.world.get()!=player.serverLevel())
            throw ServerAccess.denied("assembly_observation_unavailable","组装回执不属于当前玩家和世界，或已经过期");
        if(player.level().getGameTime()>watch.until) { WATCHES.remove(id);throw ServerAccess.denied("assembly_observation_expired","组装观察租期已结束"); }
        watch.until=player.level().getGameTime()+LEASE_TICKS;
        if(watch.invoked&&!watch.complete) {
            try { observeEnd(watch); } catch(RuntimeException|LinkageError failed) { fault(watch,failed); }
        }
        return snapshot(watch);
    }
    public static Object begin(Object packet,Object context) {
        ACTIVE.remove();
        try {
            Object source=NativeApi.call(context,null,"player");
            if(!(source instanceof ServerPlayer player)||!player.serverLevel().getServer().isSameThread()) return null;
            BlockPos pos=(BlockPos)NativeApi.call(packet,null,"pos");
            for(var watch:WATCHES.values()) if(!watch.complete&&!watch.invoked&&watch.owner.equals(player.getUUID())
                    &&watch.world.get()==player.serverLevel()&&watch.position.equals(pos)&&player.level().getGameTime()<=watch.until) {
                watch.invoked=true;watch.result.addProperty("native_request_tick",player.level().getGameTime());
                ACTIVE.set(watch);return watch;
            }
        } catch(RuntimeException|LinkageError failed) { LOG.error("Cannot observe native assembler request",failed); }
        return null;
    }
    public static void finish(Object token,boolean returned) {
        try {
            if(!(token instanceof Watch watch)) return;
            watch.result.addProperty("native_handler_completed",returned);
            if(!returned) {
                // 原生入口可能在搬完方块后才异常，保留已经捕获的真实转换，不谎称“什么都没发生”。
                watch.result.addProperty("native_handler_interrupted",true);watch.complete=true;
            } else if(!watch.complete) observeEnd(watch);
        } catch(RuntimeException|LinkageError failed) {
            if(token instanceof Watch watch) fault(watch,failed);
        } finally { ACTIVE.remove(); }
    }
    private static void observeEnd(Watch watch) {
        BlockEntity assembler=watch.assembler.get();
        if(assembler==null||assembler.isRemoved()) {
            fault(watch,new IllegalStateException("组装器已离开原位置，但没有捕获到完整原生搬移回执"));return;
        }
        Object error=NativeApi.call(assembler,null,"getLastAssemblyException");
        if(error!=null&&error!=watch.beforeFailure) {
            var component=(Component)NativeApi.field(error,null,"component");
            watch.result.addProperty("outcome","native_rejected");watch.result.addProperty("native_error",component.getString());watch.complete=true;
        } else if(watch.beforeStructure==null) {
            watch.result.addProperty("outcome","no_structure_created");watch.complete=true;
        } else if(!Boolean.TRUE.equals(ControlReflection.field(assembler,"disassembling"))) {
            watch.result.addProperty("outcome","no_disassembly_observed");watch.complete=true;
        }
    }
    public static void assembled(Level level,BlockPos source,Object result) {
        Watch watch=ACTIVE.get();
        if(watch==null||watch.world.get()!=level||!watch.position.equals(source)||result==null) return;
        try {
            Object ship=NativeApi.call(result,null,"subLevel");
            UUID id=(UUID)NativeApi.call(ship,null,"getUniqueId");
            BlockPos offset=(BlockPos)NativeApi.call(result,null,"offset");
            Object plot=NativeApi.call(ship,null,"getPlot");
            BlockPos origin=(BlockPos)NativeApi.call(plot,null,"getCenterBlock");
            // 原生返回的平移负责世界格到存储格的对应，不从移动后的包围盒中心猜设计坐标。
            watch.result.addProperty("outcome","assembled");watch.result.addProperty("structure_id",id.toString());
            watch.result.add("world_to_storage_offset",position(offset));watch.result.add("origin_storage",position(origin));watch.complete=true;
        } catch(RuntimeException|LinkageError failed) { fault(watch,failed); }
    }
    public static void disassembled(Level level,Object ship,BlockPos source,BlockPos destination,Rotation rotation) {
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()) return;
        try {
            UUID id=(UUID)NativeApi.call(ship,null,"getUniqueId");
            for(var watch:WATCHES.values()) if(watch.invoked&&!watch.complete&&watch.world.get()==level&&id.equals(watch.beforeStructure)) {
                watch.result.addProperty("outcome","disassembled");watch.result.addProperty("structure_id",id.toString());
                watch.result.add("storage_anchor",position(source));watch.result.add("world_anchor",position(destination));
                watch.result.addProperty("rotation",rotation.name());watch.complete=true;
            }
        } catch(RuntimeException|LinkageError failed) { LOG.error("Cannot observe native disassembly transform",failed); }
    }
    private static void fault(Watch watch,Throwable failed) {
        watch.result.addProperty("observation_error",failed.toString());watch.complete=true;
        LOG.error("Native assembly observation failed for {}",watch.id,failed);
    }
    private static JsonObject snapshot(Watch watch) {
        var result=watch.result.deepCopy();result.addProperty("watch_id",watch.id.toString());
        result.addProperty("native_input_observed",watch.invoked);result.addProperty("complete",watch.complete);
        ServerLevel world=watch.world.get();
        if(world!=null) result.addProperty("dimension",world.dimension().location().toString());
        result.add("assembler_position",position(watch.position));
        if(watch.beforeStructure!=null)result.addProperty("before_structure_id",watch.beforeStructure.toString());
        result.addProperty("evidence","server_native_assembler_callbacks");return result;
    }
    static JsonArray position(BlockPos point) {
        var result=new JsonArray();result.add(point.getX());result.add(point.getY());result.add(point.getZ());return result;
    }
}
