package org.maiwithu.maicraft.core.task.physics;

import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** 转子声明需要全量成员及局部坐标，不能因为规模较大而静默丢掉尾部桨叶。 */
public final class NativePropellerStateTest {
    public static void run() {
        var nativeBlocks=new LinkedHashMap<BlockPos,StructureBlockInfo>();
        for(int i=0;i<130;i++) {
            var pos=new BlockPos(i-65,2,-3);nativeBlocks.put(pos,new StructureBlockInfo(pos,Blocks.OAK_PLANKS.defaultBlockState(),null));
        }
        var result=NativePropellerState.blocks(nativeBlocks);
        if(result.size()!=130||!result.getLast().get("position").equals(List.of(64,2,-3))
                ||!result.getLast().get("block_id").equals("minecraft:oak_planks"))throw new AssertionError("转子成员或坐标被省略");
        System.out.println("NativePropellerStateTest: passed");
    }
}
