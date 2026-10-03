package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.task.physics.PhysicalAssemblyParameters.Adhesive;

/** 核对胶种、选区端点与组装方向；确认只能来自新同步胶层，不能借用旧实体或附近胶水。 */
public final class PhysicalAssemblyParametersTest {
    public static void run() {
        JsonObject input=json("{\"operation\":\"bond\",\"adhesive\":\"simulated:honey_glue\",\"first\":{\"x\":4,\"y\":2,\"z\":3},\"second\":{\"x\":-2,\"y\":0,\"z\":1},\"declarations\":[{\"position\":{\"x\":0,\"y\":0,\"z\":0},\"block_id\":\"minecraft:oak_planks\"}]}");
        var parameters=PhysicalAssemblyParameters.parse(input);
        check(parameters.region(new BlockPos(100,50,200)).equals(new AABB(98,50,201,105,53,204)),"反向点选应包含两端整格，且只按同一锚点投影");
        input.getAsJsonArray("declarations").get(0).getAsJsonObject().addProperty("block_id","minecraft:air");
        check(parameters.declarations().get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:oak_planks"),"调用者修改参数篡改了已接收的设计");
        var copy=parameters.declarations();copy.remove(0);check(parameters.declarations().size()==1,"声明访问器泄漏可变设计");
        rejects("{\"operation\":\"bond\",\"first\":{\"x\":0,\"y\":0,\"z\":0},\"second\":{\"x\":1,\"y\":0,\"z\":0}}");
        rejects("{\"operation\":\"assemble\",\"structure_id\":\"00000000-0000-4000-8000-000000000001\"}");
        rejects("{\"operation\":\"disassemble\"}");
        rejects("{\"operation\":\"bond\",\"adhesive\":\"minecraft:honey_block\",\"first\":{},\"second\":{}}");
        rejects("{\"assembler\":{\"x\":0.5,\"y\":0,\"z\":0}}");
        rejects("{\"first\":{\"x\":0,\"y\":0,\"z\":0}}");
        rejects("{\"operation\":\"inspect\",\"velocity\":10}");
        AABB region=new AABB(0,0,0,4,3,2);
        var old=new NativeAssemblyApi.Bond(UUID.randomUUID(),Adhesive.HONEY,region);
        var fresh=new NativeAssemblyApi.Bond(UUID.randomUUID(),Adhesive.HONEY,region);
        var adjacent=new NativeAssemblyApi.Bond(UUID.randomUUID(),Adhesive.HONEY,region.move(4,0,0));
        var otherKind=new NativeAssemblyApi.Bond(UUID.randomUUID(),Adhesive.SUPER,region);
        check(NativeAssemblyApi.newBonds(List.of(old),List.of(old,adjacent,otherKind),Adhesive.HONEY,region).isEmpty(),"旧胶层、旁边胶层或不同胶种不能确认本次动作");
        check(NativeAssemblyApi.newBonds(List.of(old),List.of(old,fresh),Adhesive.HONEY,region).equals(List.of(fresh)),"新同步且覆盖全部选区的胶层没有被确认");
        check(!NativeAssemblyApi.covers(new NativeAssemblyApi.Bond(UUID.randomUUID(),Adhesive.HONEY,new AABB(0,0,0,3,3,2)),Adhesive.HONEY,region),"只覆盖一部分的胶层不能冒充完整粘接");
        check(NativeAssemblyApi.failureMessage(new Failure()).equals("原生组装无法对齐"),"原生拒绝原因字段未被正确读取");
    }
    public static final class Failure { public final Component component=Component.literal("原生组装无法对齐"); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void rejects(String input) {
        try { PhysicalAssemblyParameters.parse(json(input)); }
        catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("无效物理组装目标被接受: "+input);
    }
    private static void check(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); }
}
