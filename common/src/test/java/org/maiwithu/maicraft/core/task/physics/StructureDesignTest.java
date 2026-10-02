package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonParser;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** 后续配重补丁仍核对先前声明的蒙皮；世界切换和调用者修改 JSON 都不会串改其他方案。 */
public final class StructureDesignTest {
    public static void run() {
        Object world=new Object();UUID id=UUID.randomUUID();
        var skin=JsonParser.parseString("[{\"position\":{\"x\":0,\"y\":2,\"z\":0},\"block_id\":\"minecraft:white_wool\"}]").getAsJsonArray();
        PhysicalStructureDesign.merge(world,id,skin);
        skin.get(0).getAsJsonObject().addProperty("block_id","minecraft:air");
        var ballast=JsonParser.parseString("[{\"position\":{\"x\":0,\"y\":-1,\"z\":0},\"block_id\":\"minecraft:iron_block\"}]").getAsJsonArray();
        var all=PhysicalStructureDesign.merge(world,id,ballast);
        if(all.size()!=2||!all.get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:white_wool"))
            throw new AssertionError("后续补丁丢失或篡改了已声明结构目标");
        if(PhysicalStructureDesign.merge(new Object(),id,ballast).size()!=1) throw new AssertionError("结构蓝图跨世界串用");
        var face=StructureEditTarget.face(new AABB(0,0,0,1,.5,1),Direction.UP);
        if(face.y>.5||face.y<.49) throw new AssertionError("半砖点击面错误地落在整格顶面");
    }
}
