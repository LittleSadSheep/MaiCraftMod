package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 控制意图只指定部件与原生设置，不接收点击脚本、任意 NBT 或直接施力。 */
public record PhysicalControlParameters(Operation operation,UUID structureId,UUID designId,BlockPos position,Integer value,
                                        Boolean receiver,List<String> frequencyItems) {
    public enum Operation { INSPECT, SET_SPEED, SET_THROTTLE, SET_LINK_MODE, SET_FREQUENCY }
    public PhysicalControlParameters {position=position.immutable();frequencyItems=List.copyOf(frequencyItems);}
    public static PhysicalControlParameters parse(JsonObject input) {
        for(String key:input.keySet())if(!Set.of("operation","structure_id","design_id","position","value","receiver","frequency_items").contains(key))
            throw new IllegalArgumentException("未知物理控制参数: "+key);
        Operation op=Operation.valueOf(PhysicalAssemblyParameters.text(input,"operation","inspect").toUpperCase(Locale.ROOT));
        UUID id=input.has("structure_id")?UUID.fromString(PhysicalAssemblyParameters.text(input,"structure_id",null)):null;
        UUID design=input.has("design_id")?UUID.fromString(PhysicalAssemblyParameters.text(input,"design_id",null)):null;
        if(id!=null&&design!=null)throw new IllegalArgumentException("结构沿用自身声明，不能同时指定世界 design_id");
        boolean scalar=op==Operation.SET_SPEED||op==Operation.SET_THROTTLE;
        if(input.has("value")!=scalar||input.has("receiver")!=(op==Operation.SET_LINK_MODE)
                ||input.has("frequency_items")!=(op==Operation.SET_FREQUENCY))throw new IllegalArgumentException("配置字段必须与明确的 operation 对应");
        Integer value=null;Boolean receiver=null;List<String> frequency=List.of();
        if(scalar) {
            if(!input.get("value").isJsonPrimitive()||!input.getAsJsonPrimitive("value").isNumber())throw new IllegalArgumentException("value 必须为整数");
            try {value=input.get("value").getAsBigDecimal().intValueExact();}
            catch(ArithmeticException invalid) {throw new IllegalArgumentException("value 必须为整数",invalid);}
            // 电机原生面板的零档被映射为一转；停止应使用真实离合或刹车，不能伪造一个面板没有的零转速。
            if(op==Operation.SET_SPEED?(value==0||value< -256||value>256):(value<0||value>15))throw new IllegalArgumentException("转速旋钮应为非零 -256..256，油门信号应为 0..15");
        }
        if(op==Operation.SET_LINK_MODE) {
            if(!input.get("receiver").isJsonPrimitive()||!input.getAsJsonPrimitive("receiver").isBoolean())throw new IllegalArgumentException("receiver 必须为布尔值");
            receiver=input.get("receiver").getAsBoolean();
        }
        if(op==Operation.SET_FREQUENCY) {
            if(!input.get("frequency_items").isJsonArray()||input.getAsJsonArray("frequency_items").size()!=2)
                throw new IllegalArgumentException("frequency_items 必须是有序的两个物品编号；minecraft:air 表示清空对应频率");
            var values=input.getAsJsonArray("frequency_items");var items=new ArrayList<String>();
            for(var raw:values) {
                if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString()||ResourceLocation.tryParse(raw.getAsString())==null)
                    throw new IllegalArgumentException("频率必须使用有效物品编号");
                items.add(raw.getAsString());
            }
            frequency=items;
        }
        return new PhysicalControlParameters(op,id,design,PhysicalAssemblyParameters.position(input,"position"),value,receiver,frequency);
    }
}
