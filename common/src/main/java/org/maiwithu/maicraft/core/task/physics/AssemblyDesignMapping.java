package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;

/** 继承已登记建筑的明确要求，按原生坐标转换继续追踪；不把未声明空气补成清空要求。 */
final class AssemblyDesignMapping {
    private AssemblyDesignMapping() {}
    static JsonArray declarations(PhysicalAssemblyParameters parameters,BlockPos anchor,String dimension) {
        return declarations(parameters,anchor,dimension,parameters.projectId()==null?null:BuildProjectStore.current());
    }
    static JsonArray declarations(PhysicalAssemblyParameters parameters,BlockPos anchor,String dimension,BuildProjectStore store) {
        var source=new JsonArray();
        if(parameters.projectId()!=null) {
            var project=store.load(parameters.projectId().toString(),dimension);
            source=fromProject(project.getAsJsonArray("project_targets"),anchor);
        }
        // 当前明确补丁覆盖同格旧声明，其余完整工程目标继续保留。
        return PhysicalStructureDesignStore.mergeTargets(source,parameters.declarations());
    }
    static JsonArray fromProject(JsonArray targets,BlockPos anchor) {
        var result=new JsonArray();
        for(var raw:targets) {
            var target=raw.getAsJsonObject();var cell=new JsonObject();cell.add("block_id",target.get("block_id").deepCopy());
            position(cell,new BlockPos(target.get("x").getAsInt(),target.get("y").getAsInt(),target.get("z").getAsInt()).subtract(anchor));
            Set<String> required=new LinkedHashSet<>();
            var explicit=target.getAsJsonArray(target.has("final_properties")?"final_properties":"exact_properties");
            if(explicit!=null) explicit.forEach(name->required.add(name.getAsString()));
            var saved=target.getAsJsonObject("properties");
            // 旧工程入口把方向提示单独存放；仅继承有明确提示的方向，不把其余默认状态升级为设计要求。
            if(!target.has("final_properties")) {
                for(String hint:Set.of("facing","axis","half")) if(target.has(hint)) {
                    if(saved.has(hint))required.add(hint);else if(hint.equals("half")&&saved.has("type"))required.add("type");
                }
            }
            var properties=new JsonObject();for(String name:required) {
                if(!saved.has(name))throw new IllegalArgumentException("建筑声明缺少已登记属性 "+name);
                properties.add(name,saved.get(name).deepCopy());
            }
            if(!properties.isEmpty())cell.add("properties",properties);result.add(cell);
        }
        return result;
    }
    static JsonArray assembled(JsonArray declarations,BlockPos anchor,BlockPos offset,BlockPos origin) {
        var result=new JsonArray();
        for(var raw:declarations) {
            var cell=raw.getAsJsonObject().deepCopy();position(cell,point(cell).offset(anchor).offset(offset).subtract(origin));result.add(cell);
        }
        return result;
    }
    static JsonArray movedToWorld(JsonArray declarations,BlockPos origin,BlockPos source,BlockPos destination,Rotation rotation) {
        var result=new JsonArray();
        for(var raw:declarations) {
            var cell=raw.getAsJsonObject().deepCopy();
            // 原生以整格中心作四分之一圈转动；整数格旋转保持相同结果，避免用移动后包围盒猜锚点。
            position(cell,point(cell).offset(origin).subtract(source).rotate(rotation).offset(destination));
            AssemblyDeclaredRotation.apply(cell,rotation);result.add(cell);
        }
        return result;
    }
    static BlockPos point(JsonObject cell) {
        var p=cell.getAsJsonObject("position");return new BlockPos(p.get("x").getAsBigDecimal().intValueExact(),p.get("y").getAsBigDecimal().intValueExact(),p.get("z").getAsBigDecimal().intValueExact());
    }
    static BlockPos point(JsonArray value) {
        if(value.size()!=3)throw new IllegalArgumentException("原生坐标必须有三个整数分量");
        return new BlockPos(value.get(0).getAsBigDecimal().intValueExact(),value.get(1).getAsBigDecimal().intValueExact(),value.get(2).getAsBigDecimal().intValueExact());
    }
    static void position(JsonObject cell,BlockPos pos) {
        var p=new JsonObject();p.addProperty("x",pos.getX());p.addProperty("y",pos.getY());p.addProperty("z",pos.getZ());cell.add("position",p);
    }
}
