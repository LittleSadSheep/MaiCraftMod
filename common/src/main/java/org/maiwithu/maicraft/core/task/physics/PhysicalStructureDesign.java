package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** 在当前加载的世界内合并历次明确声明的结构目标，后续补丁也要复查先前声明的配重和蒙皮。 */
final class PhysicalStructureDesign {
    private static final Map<Object,Map<UUID,Map<String,JsonObject>>> WORLDS=new WeakHashMap<>();
    private PhysicalStructureDesign() {}
    static JsonArray merge(Object world,UUID id,JsonArray edits) {
        var targets=WORLDS.computeIfAbsent(world,key->new LinkedHashMap<>()).computeIfAbsent(id,key->new LinkedHashMap<>());
        for(var raw:edits) {
            var cell=raw.getAsJsonObject();var p=cell.getAsJsonObject("position");
            String key=p.get("x").getAsInt()+","+p.get("y").getAsInt()+","+p.get("z").getAsInt();
            targets.put(key,cell.deepCopy());
        }
        var complete=new JsonArray();targets.values().forEach(cell->complete.add(cell.deepCopy()));return complete;
    }
}
