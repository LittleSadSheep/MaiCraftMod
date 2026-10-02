package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** 后续配重补丁仍核对先前声明的蒙皮；调用者修改 JSON 不会串改已冻结的方案。 */
public final class StructureDesignTest {
    public static void run() {
        var skin=JsonParser.parseString("[{\"position\":{\"x\":0,\"y\":2,\"z\":0},\"block_id\":\"minecraft:white_wool\"}]").getAsJsonArray();
        var frozen=PhysicalStructureDesignStore.mergeTargets(new JsonArray(),skin);
        skin.get(0).getAsJsonObject().addProperty("block_id","minecraft:air");
        var ballast=JsonParser.parseString("[{\"position\":{\"x\":0,\"y\":-1,\"z\":0},\"block_id\":\"minecraft:iron_block\"}]").getAsJsonArray();
        var all=PhysicalStructureDesignStore.mergeTargets(frozen,ballast);
        if(all.size()!=2||!all.get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:white_wool"))
            throw new AssertionError("后续补丁丢失或篡改了已声明结构目标");
        var face=StructureEditTarget.face(new AABB(0,0,0,1,.5,1),Direction.UP);
        if(face.y>.5||face.y<.49) throw new AssertionError("半砖点击面错误地落在整格顶面");
    }
}
