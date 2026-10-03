package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 明确胶种、选区和组装器偏移；同一组坐标只能属于选定的世界锚点或一个已观察结构。 */
public record PhysicalAssemblyParameters(Operation operation,Adhesive adhesive,UUID structureId,
        BlockPos first,BlockPos second,BlockPos assembler,UUID projectId,UUID designId,JsonArray declarations) {
    public enum Operation { INSPECT, BOND, ASSEMBLE, DISASSEMBLE }
    public enum Adhesive {
        SUPER("create:super_glue","com.simibubi.create.content.contraptions.glue.SuperGlueEntity",
                "com.simibubi.create.content.contraptions.glue.SuperGlueSelectionPacket"),
        HONEY("simulated:honey_glue","dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity",
                "dev.simulated_team.simulated.network.packets.honey_glue.HoneyGlueSpawnPacket");
        public final String item,entityType,packetType;
        Adhesive(String item,String entityType,String packetType) { this.item=item;this.entityType=entityType;this.packetType=packetType; }
        static Adhesive parse(String value) {
            for(var kind:values()) if(kind.item.equals(value)) return kind;
            throw new IllegalArgumentException("adhesive 必须是 create:super_glue 或 simulated:honey_glue");
        }
    }
    public PhysicalAssemblyParameters {
        first=first.immutable();second=second.immutable();assembler=assembler.immutable();declarations=declarations.deepCopy();
    }
    @Override public JsonArray declarations() { return declarations.deepCopy(); }
    public AABB region(BlockPos origin) { return AABB.encapsulatingFullBlocks(origin.offset(first),origin.offset(second)); }
    public static PhysicalAssemblyParameters parse(JsonObject input) {
        for(String key:input.keySet()) if(!Set.of("operation","adhesive","structure_id","first","second","assembler","project_id","design_id","declarations").contains(key))
            throw new IllegalArgumentException("未知物理组装参数: "+key);
        Operation operation=Operation.valueOf(text(input,"operation","inspect").toUpperCase(Locale.ROOT));
        Adhesive adhesive=input.has("adhesive")?Adhesive.parse(text(input,"adhesive",null)):null;
        if(adhesive!=null&&operation!=Operation.BOND)throw new IllegalArgumentException("adhesive 只用于明确的 bond 操作");
        UUID id=input.has("structure_id")?UUID.fromString(text(input,"structure_id",null)):null;
        UUID project=input.has("project_id")?UUID.fromString(text(input,"project_id",null)):null;
        UUID design=input.has("design_id")?UUID.fromString(text(input,"design_id",null)):null;
        if((project!=null||design!=null)&&id!=null) throw new IllegalArgumentException("世界建筑 project_id/design_id 只能在组装前绑定；已有结构沿用自身的完整声明");
        if(operation==Operation.BOND&&(adhesive==null||!input.has("first")||!input.has("second")))
            throw new IllegalArgumentException("粘接必须明确胶种及 first、second 两个选区端点");
        // 原生组装包会切换状态；参数先区分创建和拆回，不能把重复提交误变成相反动作。
        if(operation==Operation.ASSEMBLE&&id!=null) throw new IllegalArgumentException("assemble 创建尚未组装的世界方块，不能指定已有结构 UUID");
        if(operation==Operation.DISASSEMBLE&&id==null) throw new IllegalArgumentException("disassemble 必须指定观察到的结构 UUID");
        if(input.has("first")!=input.has("second")) throw new IllegalArgumentException("first 与 second 必须成对提供");
        if(input.has("declarations")&&!input.get("declarations").isJsonArray()) throw new IllegalArgumentException("declarations 必须是明确目标数组");
        JsonArray declarations=input.has("declarations")?input.getAsJsonArray("declarations"):new JsonArray();
        if(declarations.size()>BuildingBudgets.current().maxTargets()) throw new IllegalArgumentException("明确声明的目标超出建筑目标预算");
        // 只保留作者明确给出的格子与状态；不从胶水选区推导“其他地方都必须为空”。
        declarations=PhysicalStructureDesignStore.mergeTargets(new JsonArray(),declarations);
        for(var cell:declarations) {
            if(!Set.of("position","block_id","properties").containsAll(cell.getAsJsonObject().keySet()))
                throw new IllegalArgumentException("声明目标只接受 position、block_id 和 properties");
            position(cell.getAsJsonObject(),"position");
        }
        return new PhysicalAssemblyParameters(operation,adhesive,id,position(input,"first"),position(input,"second"),position(input,"assembler"),project,design,declarations);
    }
    static BlockPos position(JsonObject input,String field) {
        if(!input.has(field)) return BlockPos.ZERO;
        if(!input.get(field).isJsonObject()) throw new IllegalArgumentException(field+" 必须是坐标对象");
        var value=input.getAsJsonObject(field);
        if(!value.keySet().equals(Set.of("x","y","z"))) throw new IllegalArgumentException(field+" 必须包含整数 x、y、z");
        int[] point=new int[3];int index=0;
        for(String axis:new String[]{"x","y","z"}) {
            if(!value.get(axis).isJsonPrimitive()) throw new IllegalArgumentException(field+" 坐标必须是整数数字");
            var raw=value.getAsJsonPrimitive(axis);
            if(!raw.isNumber()) throw new IllegalArgumentException(field+" 坐标必须是整数数字");
            int coordinate;
            try { coordinate=raw.getAsBigDecimal().intValueExact(); }
            catch(ArithmeticException fractional) { throw new IllegalArgumentException(field+" 坐标必须是范围内的整数",fractional); }
            if(Math.abs((long)coordinate)>BuildingBudgets.current().maxRadius()) throw new IllegalArgumentException(field+" 偏移超出建筑范围预算");
            point[index++]=coordinate;
        }
        return new BlockPos(point[0],point[1],point[2]);
    }
    static String text(JsonObject input,String key,String fallback) {
        if(!input.has(key)) return fallback;
        if(!input.get(key).isJsonPrimitive()||!input.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key+" 必须是文字");
        return input.get(key).getAsString();
    }
}
