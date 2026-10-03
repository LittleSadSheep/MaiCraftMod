package org.maiwithu.maicraft.core.task.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 桨叶成型后移入原生运动装置；完整返回实际成员，不能只用轴承 running 标志冒充整副桨叶已连接。 */
final class NativePropellerState {
    private NativePropellerState() {}
    static Map<String,Object> capture(Object bearing,boolean assembled) {
        Object moved=NativeApi.call(bearing,null,"getMovedContraption");
        if(!(moved instanceof Entity entity))return Map.of("state",assembled?"awaiting_native_entity_sync":"not_assembled");
        try {
            Object rotor=NativeApi.call(moved,null,"getContraption");
            if(rotor==null)return Map.of("state","awaiting_native_contraption_sync","entity_uuid",entity.getUUID().toString());
            List<Map<String,Object>> blocks=blocks((Map<?,?>)NativeApi.call(rotor,null,"getBlocks"));
            return Map.of("state","observed","entity_uuid",entity.getUUID().toString(),"blocks",blocks,
                    "block_count",blocks.size(),"coordinate_frame","local offsets relative to the native rotor anchor",
                    "scope","all blocks in the synchronized native contraption; independent from the stationary blueprint diff");
        } catch(RuntimeException unavailable) {
            return Map.of("state","unavailable","entity_uuid",entity.getUUID().toString(),"reason",unavailable.toString());
        }
    }
    static List<Map<String,Object>> blocks(Map<?,?> blocks) {
        var result=new ArrayList<Map<String,Object>>();
        // 这里是原生装配结果，不截取前几片桨叶；世界蓝图缺格时，模型可与这些实际成员逐格对账。
        for(var entry:blocks.entrySet()) {
            if(!(entry.getKey() instanceof BlockPos pos)||!(entry.getValue() instanceof StructureBlockInfo info))
                throw new IllegalStateException("原生转子成员类型不可识别");
            result.add(Map.of("position",List.of(pos.getX(),pos.getY(),pos.getZ()),
                    "block_id",BuiltInRegistries.BLOCK.getKey(info.state().getBlock()).toString(),"block_state",info.state().toString()));
        }
        return List.copyOf(result);
    }
}
