package org.maiwithu.maicraft.core.task.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import static org.maiwithu.maicraft.core.integration.machine.control.ControlReflection.*;

/** 只读完整配键并构造原生玩家请求；频率取自真实物品副本，不写方块实体或服务器字段。 */
final class NativeTypewriterControl {
    static final String TYPE="dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity";
    private static final String ENTRY="dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterEntries$KeyboardEntry";
    private static final String PACKETS="dev.simulated_team.simulated.network.packets.linked_typewriter.";
    private NativeTypewriterControl() {}
    static Map<Integer,Object> entries(BlockEntity entity) {
        Object entries=call(entity,"getTypewriterEntries");
        var result=new LinkedHashMap<Integer,Object>();
        // 继承完整键表再合并本次单键修改；原生保存包会替换全表，漏读旧键会删掉其他控制。
        for(Object key:(List<?>)call(entries,"getEntries"))
            result.put(((Number)call(key,"getGLFWKeyCode")).intValue(),key);
        return result;
    }
    static boolean owned(BlockEntity entity,LocalPlayer player) { return Boolean.TRUE.equals(call(entity,"checkUser",player.getUUID())); }
    static boolean inUse(BlockEntity entity) { return Boolean.TRUE.equals(call(entity,"isInUse")); }
    static boolean inRange(BlockEntity entity,LocalPlayer player) {
        return Boolean.TRUE.equals(call(type(TYPE),"playerInRange",player,entity.getLevel(),entity.getBlockPos()));
    }
    static CustomPacketPayload save(BlockEntity entity,int key,ItemStack first,ItemStack second) {
        Map<Integer,Object> merged=entries(entity);
        if(first.isEmpty()&&second.isEmpty())merged.remove(key);
        else merged.put(key,call(type(ENTRY),"createFromCodec",first.copy(),second.copy(),key));
        return payload("TypewriterKeySavePacket",merged,entity.getBlockPos(),false);
    }
    static boolean matches(BlockEntity entity,int key,ItemStack first,ItemStack second) {
        Object entry=entries(entity).get(key);
        if(first.isEmpty()&&second.isEmpty())return entry==null;
        if(entry==null)return false;
        Object desired=call(type(ENTRY),"createFromCodec",first,second,key);
        return call(entry,"getNetworkKey").equals(call(desired,"getNetworkKey"));
    }
    static CustomPacketPayload key(BlockEntity entity,int key,boolean pressed) {
        return payload("TypewriterKeyInteractionPacket",entity.getBlockPos(),key,0,pressed?1:0);
    }
    static CustomPacketPayload disconnect(BlockEntity entity) { return payload("TypewriterDisconnectUser",entity.getBlockPos()); }
    static void detachClient(BlockEntity entity) {
        // 原生 Esc 同时退出客户端键盘接管；只清理仍指向本次方块的客户端会话，不碰新接管的打字机。
        var handler=type("dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterInteractionHandler");
        if(field(handler,"TYPEWRITER") instanceof WeakReference<?> reference&&reference.get()==entity)
            call(handler,"associateTypewriter",(Object)null);
    }
    private static CustomPacketPayload payload(String name,Object... args) {
        return (CustomPacketPayload)ControlReflection.construct(PACKETS+name,args);
    }
    static Map<String,Object> state(BlockEntity entity) {
        var bindings=new ArrayList<Map<String,Object>>();
        entries(entity).forEach((code,entry)->bindings.add(Map.of("key",TypewriterKeyInput.name(code),"key_code",code,
                "frequency_items",List.of(item((ItemStack)call(entry,"getFirstAsItemStack")),item((ItemStack)call(entry,"getSecondAsItemStack"))))));
        return Map.of("in_use",inUse(entity),"bindings",bindings,"binding_count",bindings.size(),
                "key_state_observation","native pressed keys are not synchronized to clients; packet dispatch is not reception or vehicle motion",
                "supported_operations",List.of("inspect","bind_typewriter_key","press_typewriter_keys"));
    }
    private static Map<String,Object> item(ItemStack stack) {
        var color=stack.get(DataComponents.DYED_COLOR);
        return Map.of("item_id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"dyed_color",color==null?-1:color.rgb());
    }
}
