package org.maiwithu.maicraft.server.physics;

import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 未开启轴承时只读前方转子平面的桨叶；不调用会安排方块刻或搬运库存的原生装配过程。 */
final class PreflightRotorPreview {
    private PreflightRotorPreview() {}
    static double sailPower(BlockEntity bearing,Direction facing) {
        var level=bearing.getLevel(); BlockPos anchor=bearing.getBlockPos().relative(facing);
        var queue=new ArrayDeque<BlockPos>(); var visited=new HashSet<BlockPos>(); queue.add(anchor);
        double power=0;
        while(!queue.isEmpty()) {
            BlockPos position=queue.removeFirst(); if(!visited.add(position)) continue;
            if(visited.size()>4096) throw new IllegalArgumentException("未装配转子超过只读预览预算");
            if(position.distManhattan(anchor)>32) throw new IllegalArgumentException("未装配转子超出预览范围");
            if(!level.hasChunkAt(position)) throw new IllegalArgumentException("未装配转子区块未加载");
            var state=level.getBlockState(position); if(state.isAir()) continue;
            BlockEntity entity=level.getBlockEntity(position);
            CompoundTag tag=entity==null?new CompoundTag():entity.saveWithFullMetadata(level.registryAccess());
            power+=((Number)NativeApi.call(bearing,null,"getSailPower",new StructureBlockInfo(position.subtract(anchor),state,tag))).doubleValue();
            for(Direction side:Direction.values()) if(side.getAxis()!=facing.getAxis()) queue.add(position.relative(side));
        }
        return power;
    }
}
