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
    // 模型先确定胶区与坐标框架再请求原生转换；说明完整设计继承和单次输入回执，避免恢复时反向切换组装器。
    static JsonObject contract() {
        var out=new JsonObject();out.addProperty("summary","把 LLM 声明的世界方块用强力胶/蜂蜜胶粘接，再操作物理组装器创建结构；也可观察或将结构拆回世界方块。inspect（默认）只读；bond 备实际胶水、走到两个端点、提交一次原生选区并确认胶实体与材料；assemble/disassemble 先保存整份声明、观察组装器、提交一次原生切换，再确认是否发生坐标框架转换。"
                + "\n\n参数均在 goal.parameters；goal.outcome 是用途文字，不能代替参数。未声明的操作字段会被拒绝；无能力专属 preferences 或硬约束。可选字段省略才用默认值，null 不代表省略，数值用 JSON number，布尔值用 true/false。当前独立解析器拒绝 parameters 中的 auto_respawn/recover_after_death，公共运行时授权需放在 goal.preferences。 坐标二选一：有 structure_id 时不写 target，全部偏移相对 origin_storage；没有 structure_id 时必须给 goal.target.kind=coordinates|landmark|area|current_place，偏移相对此世界锚点。coordinates 用 position:{x:整数,y:整数,z:整数,dimension?:字符串}；landmark/area 用已有 label；current_place 取适配时玩家格。只支持当前维度。"
                + "\n\n真实粘接/组装要求原生材料、建造权限、加载和可达视线；移动结构先等停稳，超过 200 游戏刻仍运动则停止。inspect 不自动靠近。已有同种胶完整覆盖选区时直接返回已经粘接，不再消耗。组装是原生切换请求，无法确认时只追踪原请求，不再点击以免反向拆回。"
                + "\n\n查看 native_submitted、native_observation、structure_changed、completed_effects、design_declaration、declared_structure_diff；bond 另看 native_confirmed、already_bonded_observed、glue_before/after、material.before/after、material_settlement。handled/no_conversion 可正常返回但 structure_changed=false；胶层存在或请求处理完成不能证明车能开、船能飞。创建成功返回真实新 structure_id，拆回返回世界锚点/设计编号并旋转声明属性。转换已确认而后续读取失败时保留已完成效果和 after_observation_unknown。"
                + "\n\n以下是完整 plan 工具参数；UUID 和局部位置来自曾观察到的飞艇，仅在重新观察确认仍属当前世界同一结构时使用。其他现场必须换成真实观察值。plan 只登记计划，执行使用返回的 plan_id；不要编造执行编号。"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:physical_assembly\",\"outcome\":\"读取已观察飞艇的胶层和组装器\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"inspect\",\"assembler\":{\"x\":-1,\"y\":-5,\"z\":-1},\"first\":{\"x\":-4,\"y\":-6,\"z\":-2},\"second\":{\"x\":-1,\"y\":-4,\"z\":-1}}}}"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:physical_assembly\",\"outcome\":\"在明确的艇内选区使用蜂蜜胶\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"bond\",\"adhesive\":\"simulated:honey_glue\",\"first\":{\"x\":-1,\"y\":-6,\"z\":-1},\"second\":{\"x\":-1,\"y\":-4,\"z\":-1}}}}");
        var targets=new JsonArray();TARGETS.stream().sorted().forEach(targets::add);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());
        out.addProperty("execution_boundary","inspect 仅读；其余操作使用真实物品及原生协议，不直接生成结构或写 NBT。首次动作前先保存完整设计，存储失败不会提交动作；原生转换后存储失败保留转换事实。取消/死亡/换世界不能撤销胶层、材料或已转换结构；恢复先核对原生回执和新 UUID，禁止机械重放组装切换。");
        var fields=new JsonObject();
        field(fields,"operation","string","字符串，不区分大小写：inspect（默认）、bond、assemble、disassemble。assemble 必须使用世界 target 且无 structure_id；disassemble 必须有 structure_id 且无 target。");
        field(fields,"structure_id","string","可选真实 UUID 字符串；与 goal.target、project_id、design_id 互斥。已有结构的偏移基于 origin_storage，声明沿用该结构保存的整机记录。");
        field(fields,"adhesive","string","仅 bond 必填，其他操作禁止。字符串必须为 create:super_glue 或 simulated:honey_glue，不自动换胶种；使用当前安装的实际物品和实体协议。");
        field(fields,"first","object","恰含整数 x/y/z 的对象；与 second 成对。bond 必填，其他操作可选；省略两者都为 {x:0,y:0,z:0}，只观察原点格的胶区，不代表全船。各轴绝对值≤BuildingBudgets.maxRadius，默认 512 格。");
        field(fields,"second","object","与 first 同坐标框架、类型和范围，两端方块均包含在选区内；反向端点允许。蜂蜜胶沿原生规则允许空气端点。null 不代表省略。");
        field(fields,"assembler","object","可选整数 {x,y,z} 偏移，默认三轴 0，范围同 first。用于 inspect/assemble/disassemble 的物理组装器；bond 解析但不使用此字段，不决定胶区或世界锚点。");
        field(fields,"project_id","string","可选已保存建筑工程 UUID，只用于世界锚点；与 structure_id 互斥，可与 design_id 共存。读工程作者目标，再用本次 declarations 覆盖同格。");
        field(fields,"design_id","string","可选既有世界设计 UUID，来自此前粘接/拆回回执；与 structure_id 互斥。先保留该设计，再合入 project_id 目标和本次 declarations。inspect 只合并读取，不保存修改。");
        field(fields,"declarations","array","可选数组，默认 []；每项 {position:{x,y,z},block_id,properties?:{属性名:字符串值}}，坐标范围同 first，原始项数≤BuildingBudgets.maxTargets（默认 262144）。保存/比较声明，不施工；同格后项完整替换前项，包括旧属性，未声明格不要求为空。minecraft:air 是明确空气要求。");
        out.add("parameters",fields);return out;
    }
    private static void field(JsonObject out,String name,String type,String description) {
        var field=new JsonObject();field.addProperty("type",type);field.addProperty("description",description);out.add(name,field);
    }
}
