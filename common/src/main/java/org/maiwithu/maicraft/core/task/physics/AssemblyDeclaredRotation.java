package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** 拆回时只旋转作者声明的方向要求，不把原生修补或当前实际状态写回设计以制造匹配。 */
final class AssemblyDeclaredRotation {
    private AssemblyDeclaredRotation() {}
    static void apply(JsonObject cell,Rotation rotation) {
        if(rotation==Rotation.NONE||!cell.has("properties"))return;
        var block=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(cell.get("block_id").getAsString()))
                .orElseThrow(()->new IllegalArgumentException("无法旋转已卸载方块的声明属性"));
        BlockState state=block.defaultBlockState();var authored=cell.getAsJsonObject("properties");
        for(var entry:authored.entrySet()) {
            var property=block.getStateDefinition().getProperty(entry.getKey());
            if(property==null)throw new IllegalArgumentException("无法旋转未知声明属性: "+entry.getKey());
            state=set(state,property,entry.getValue().getAsString());
        }
        BlockState rotated=state.rotate(rotation);var result=new JsonObject();
        for(String key:authored.keySet()) {
            // 红石线等用 north/east 作为属性名，旋转后要求也应移到新方位；未声明的其他连接不补入。
            Direction side=Direction.byName(key);String destination=side==null?key:rotation.rotate(side).getName();
            var property=block.getStateDefinition().getProperty(destination);
            if(property==null)throw new IllegalArgumentException("无法表达旋转后的声明属性: "+destination);
            result.addProperty(destination,name(property,rotated.getValue(property)));
        }
        cell.add("properties",result);
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state,Property<T> property,String value) {
        return state.setValue(property,property.getValue(value).orElseThrow(()->new IllegalArgumentException("无效声明属性值: "+property.getName()+"="+value)));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String name(Property property,Comparable value) {return property.getName(value);}
}
