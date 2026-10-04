package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.integration.create.CreateManualInput;

/** 控制意图只指定部件与原生设置，不接收点击脚本、任意 NBT 或直接施力。 */
public record PhysicalControlParameters(Operation operation,UUID structureId,UUID designId,BlockPos position,Integer value,
                                        Boolean receiver,List<String> frequencyItems,int crankTicks,boolean requireOnboard,String itemId,
                                        TypewriterKeyInput typewriterInput) {
    public enum Operation { INSPECT, SET_SPEED, SET_THROTTLE, SET_LINK_MODE, SET_FREQUENCY, SET_BURNER_VOLUME,
        ASSEMBLE_PROPELLER, DISASSEMBLE_PROPELLER, TURN_CRANK, SET_TIRE, SET_SPRING_ANGLE,
        BIND_TYPEWRITER_KEY, PRESS_TYPEWRITER_KEYS }
    public PhysicalControlParameters {position=position.immutable();frequencyItems=List.copyOf(frequencyItems);}
    public static PhysicalControlParameters parse(JsonObject input) {
        for(String key:input.keySet())if(!Set.of("operation","structure_id","design_id","position","value","receiver","frequency_items","duration_seconds","require_onboard","item_id","key","keys","observe_positions").contains(key))
            throw new IllegalArgumentException("未知物理控制参数: "+key);
        Operation op=Operation.valueOf(PhysicalAssemblyParameters.text(input,"operation","inspect").toUpperCase(Locale.ROOT));
        boolean typewriter=op==Operation.BIND_TYPEWRITER_KEY||op==Operation.PRESS_TYPEWRITER_KEYS;
        // 配键与按键是两种独立意图，其他部件设置不能夹带一次驾驶输入。
        if(!typewriter&&(input.has("key")||input.has("keys")||input.has("observe_positions")))throw new IllegalArgumentException("key/keys/observe_positions 仅供打字机操作");
        TypewriterKeyInput keyInput=typewriter?TypewriterKeyInput.parse(input,op==Operation.BIND_TYPEWRITER_KEY):null;
        // 轮胎取放必须点名最终轮胎种类；空手拆胎用 air，其他控制不能夹带一次额外物品交互。
        if(input.has("item_id")!=(op==Operation.SET_TIRE))throw new IllegalArgumentException("item_id 仅供 set_tire 且必须明确提供");
        if(input.has("item_id")&&(!input.get("item_id").isJsonPrimitive()||!input.getAsJsonPrimitive("item_id").isString()))
            throw new IllegalArgumentException("item_id 必须是物品编号字符串");
        String itemId=op==Operation.SET_TIRE?PhysicalAssemblyParameters.text(input,"item_id",null):null;
        if(itemId!=null) {
            var parsed=ResourceLocation.tryParse(itemId);
            if(parsed==null)throw new IllegalArgumentException("item_id 必须是有效物品编号");
            itemId=parsed.toString();
        }
        // 只有手摇明确允许有界重复；旋钮、组装和频率设置不能通过附带持续时间变成反复点击。
        if(input.has("duration_seconds")&&op!=Operation.TURN_CRANK&&op!=Operation.PRESS_TYPEWRITER_KEYS)
            throw new IllegalArgumentException("duration_seconds 仅用于手摇或有限保持打字机按键");
        int crankTicks=op==Operation.TURN_CRANK?CreateManualInput.durationTicks(input):0;
        UUID id=input.has("structure_id")?UUID.fromString(PhysicalAssemblyParameters.text(input,"structure_id",null)):null;
        // 起飞试验可明确要求身体留在同一艘艇上；这里只核对原生接触或乘坐，不判断设计是否能飞。
        boolean onboard=false;
        if(input.has("require_onboard")) {
            if(!input.get("require_onboard").isJsonPrimitive()||!input.getAsJsonPrimitive("require_onboard").isBoolean())
                throw new IllegalArgumentException("require_onboard 必须为布尔值");
            onboard=input.get("require_onboard").getAsBoolean();
            if(onboard&&id==null)throw new IllegalArgumentException("require_onboard 需要 structure_id");
        }
        UUID design=input.has("design_id")?UUID.fromString(PhysicalAssemblyParameters.text(input,"design_id",null)):null;
        if(id!=null&&design!=null)throw new IllegalArgumentException("结构沿用自身声明，不能同时指定世界 design_id");
        boolean scalar=op==Operation.SET_SPEED||op==Operation.SET_THROTTLE||op==Operation.SET_BURNER_VOLUME||op==Operation.SET_SPRING_ANGLE;
        if(input.has("value")!=scalar||input.has("receiver")!=(op==Operation.SET_LINK_MODE)
                ||input.has("frequency_items")!=(op==Operation.SET_FREQUENCY||op==Operation.BIND_TYPEWRITER_KEY))throw new IllegalArgumentException("配置字段必须与明确的 operation 对应");
        Integer value=null;Boolean receiver=null;List<String> frequency=List.of();
        if(scalar) {
            if(!input.get("value").isJsonPrimitive()||!input.getAsJsonPrimitive("value").isNumber())throw new IllegalArgumentException("value 必须为整数");
            try {value=input.get("value").getAsBigDecimal().intValueExact();}
            catch(ArithmeticException invalid) {throw new IllegalArgumentException("value 必须为整数",invalid);}
            // 电机原生面板的零档被映射为一转；停止应使用真实离合或刹车，不能伪造一个面板没有的零转速。
            // 燃烧器上限和刻度来自当前原生服务器配置；接单时只验证其固定最小供气量，现场再读取面板。
            boolean invalid=switch(op) {
                case SET_SPEED -> value==0||value< -256||value>256;
                case SET_BURNER_VOLUME -> value<5;
                // 扭力弹簧旋钮定义正负两侧的最大舵角；回中由停转且未通电的原生弹簧执行。
                case SET_SPRING_ANGLE -> value<1||value>360;
                default -> value<0||value>15;
            };
            if(invalid)throw new IllegalArgumentException("转速旋钮应为非零 -256..256，油门信号应为 0..15，燃烧器容积至少为 5，弹簧限角应为 1..360 度");
        }
        if(op==Operation.SET_LINK_MODE) {
            if(!input.get("receiver").isJsonPrimitive()||!input.getAsJsonPrimitive("receiver").isBoolean())throw new IllegalArgumentException("receiver 必须为布尔值");
            receiver=input.get("receiver").getAsBoolean();
        }
        if(op==Operation.SET_FREQUENCY||op==Operation.BIND_TYPEWRITER_KEY) {
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
        return new PhysicalControlParameters(op,id,design,PhysicalAssemblyParameters.position(input,"position"),value,receiver,frequency,crankTicks,onboard,itemId,keyInput);
    }
}
