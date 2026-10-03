package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.task.physics.PhysicalAssemblyParameters;
import org.maiwithu.maicraft.core.task.physics.PhysicalAssemblerTaskRecord;
import org.maiwithu.maicraft.core.task.physics.PhysicalBondTaskRecord;

/** 模型决定粘接选区和组装目标；执行器负责原生取料、走位、请求和完整设计回执。 */
final class PhysicalAssemblyAbilityAdapter {
    static final String ABILITY="maicraft:physical_assembly";
    private static final Set<String> TARGETS=Set.of("coordinates","landmark","area","current_place");
    private PhysicalAssemblyAbilityAdapter() {}
    static void validate(Goal goal) {
        var parameters=PhysicalAssemblyParameters.parse(goal.parameters());
        validateTarget(goal,parameters.structureId());
    }
    static void validateTarget(Goal goal,UUID structureId) {
        if(structureId!=null) {
            if(goal.target()!=null)throw new IllegalArgumentException("structure_id 使用该结构的局部坐标，不能同时指定世界锚点");
        } else if(goal.target()==null||!TARGETS.contains(goal.target().kind()))
            throw new IllegalArgumentException("未组装的方块需要明确的世界锚点或已记地标");
    }
    static IntentAction adapt(Goal goal,LocalPlayer player,IntentRuntime runtime) {
        validate(goal);var parameters=PhysicalAssemblyParameters.parse(goal.parameters());
        String dimension=player.level().dimension().location().toString();BlockPos anchor=anchor(goal,player,runtime,parameters.structureId());
        String call="physical-assembly-"+UUID.randomUUID();long deadline=player.level().getGameTime()+20*60*15;
        return new IntentAction.Native(parameters.operation()==PhysicalAssemblyParameters.Operation.BOND
                ?new PhysicalBondTaskRecord(call,deadline,parameters,anchor,dimension)
                :new PhysicalAssemblerTaskRecord(call,deadline,parameters,anchor,dimension));
    }
    static BlockPos anchor(Goal goal,LocalPlayer player,IntentRuntime runtime,UUID structureId) {
        String dimension=player.level().dimension().location().toString();BlockPos anchor=BlockPos.ZERO;
        // 组装与控制共用坐标绑定；读到船体存储坐标时绝不能把它误作角色要前往的世界位置。
        if(structureId==null) {
            var target=goal.target();Goal.WorldPosition at;
            if(target.kind().equals("coordinates"))at=target.position();
            else if(target.kind().equals("current_place")) {
                var pos=player.blockPosition();at=new Goal.WorldPosition(pos.getX(),pos.getY(),pos.getZ(),dimension);
            } else {var place=runtime.landmark(target.label());at=place==null?null:place.position();}
            if(at==null||at.dimension()!=null&&!dimension.equals(at.dimension()))throw new IllegalArgumentException("世界锚点未解析或属于另一维度");
            anchor=new BlockPos(at.x(),at.y(),at.z());
        }
        return anchor;
    }
    static JsonObject contract() {
        var out=new JsonObject();out.addProperty("summary","原生强力胶/蜂蜜胶选区粘接、物理组装器创建/拆回结构及胶层观察。LLM决定布局与修改，Mod只执行原生操作并返回实际结果和完整声明差异；默认起飞/行驶前使用。");
        var targets=new JsonArray();TARGETS.stream().sorted().forEach(targets::add);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());
        out.addProperty("execution_boundary","inspect is read-only. bond uses actual items and native glue packets. assemble/disassemble use the native lever request and server observation; a handled request or glue entity is not proof of a functioning vehicle.");
        var fields=new JsonObject();
        field(fields,"operation","string","inspect (default), bond, assemble, disassemble. Disassembly requires structure_id; assembly requires a world target.");
        field(fields,"structure_id","string","Observed Sable UUID; omit target. first/second/assembler/declarations positions are offsets from origin_storage.");
        field(fields,"adhesive","string","Required for bond: create:super_glue or simulated:honey_glue. The selected kind is never silently replaced.");
        field(fields,"first","object","Integer {x,y,z} offset of the first glue-region corner; required with second for bond. Other operations may use both to inspect a region.");
        field(fields,"second","object","Integer {x,y,z} offset of the other corner. Both endpoint cells are included. Honey glue supports native air-endpoint selection.");
        field(fields,"assembler","object","Integer {x,y,z} offset of the physics assembler; default {0,0,0}. A world target is the anchor, not necessarily the assembler itself.");
        field(fields,"project_id","string","Optional saved build project UUID before assembly; inherit all authored targets in this world, preserving explicitly required properties.");
        field(fields,"design_id","string","Optional world_design_id returned by prior bonding/disassembly; inherit and merge the whole saved world design before reassembly.");
        field(fields,"declarations","array","Optional explicit {position:{x,y,z},block_id,properties?:{name:value}} targets. Merge same-cell updates into inherited design; undeclared air is never a clearing instruction.");
        out.add("parameters",fields);return out;
    }
    private static void field(JsonObject out,String name,String type,String description) {
        var field=new JsonObject();field.addProperty("type",type);field.addProperty("description",description);out.add(name,field);
    }
}
