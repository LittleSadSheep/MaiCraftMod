package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** 组装与粘接后的默认回执复查完整明确声明；未知格子照实标记，不把动作确认当作设计达标。 */
final class AssemblyDeclarationView {
    private AssemblyDeclarationView() {}
    static List<Map<String,Object>> diff(PhysicalAssemblyFrame frame,JsonArray declarations) {
        var result=new ArrayList<Map<String,Object>>();
        for(var raw:declarations) {
            var cell=raw.getAsJsonObject();var local=AssemblyDesignMapping.point(cell);
            BlockState actual=null;String unreadable=null;
            // 方块观察失败也要交付其余声明和已经确认的动作，不让一次后置读取异常吞掉完整回执。
            try {actual=frame==null||!frame.loaded(frame.storage(local))?null:frame.level().getBlockState(frame.storage(local));}
            catch(RuntimeException missing){unreadable=missing.toString();}
            var block=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(cell.get("block_id").getAsString()));
            boolean matches=actual!=null&&block.isPresent()&&actual.is(block.get());
            if(matches&&cell.has("properties"))for(var entry:cell.getAsJsonObject("properties").entrySet()) {
                var property=actual.getBlock().getStateDefinition().getProperty(entry.getKey());
                if(property==null||!name(property,actual.getValue(property)).equals(entry.getValue().getAsString())) {matches=false;break;}
            }
            var row=new LinkedHashMap<String,Object>(Map.of("expected",cell.deepCopy(),"actual",actual==null?"unknown_unloaded":actual.toString(),"matches",matches));
            if(unreadable!=null)row.put("observation_error",unreadable);result.add(row);
        }
        return result;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String name(Property property,Comparable value) {return property.getName(value);}
}
