package org.maiwithu.maicraft.server.physics;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 未开启轴承时只读前方转子平面的桨叶；不调用会安排方块刻或搬运库存的原生装配过程。 */
final class PreflightRotorPreview {
    private PreflightRotorPreview() {}
    static double sailPower(BlockEntity bearing,Direction facing) {
        return sailPower(bearing,bearing.getLevel(),bearing.getBlockPos(),facing);
    }
    /** 候选轴承与桨叶移动必须读同一份补丁视图，不能沿用真实世界旧平面的数量。 */
    static double sailPower(BlockEntity bearing,BlockGetter view,BlockPos position,Direction facing) {
        var level=bearing.getLevel();
        return scan(position.relative(facing),facing,pos->{
            if(!level.hasChunkAt(pos))throw new IllegalArgumentException("未装配转子区块未加载");
            var state=view.getBlockState(pos);BlockEntity entity=view.getBlockEntity(pos);
            CompoundTag tag=entity==null?new CompoundTag():entity.saveWithFullMetadata(level.registryAccess());
            return new StructureBlockInfo(pos,state,tag);
        },info->((Number)NativeApi.call(bearing,null,"getSailPower",info)).doubleValue());
    }
    static double scan(BlockPos anchor,Direction facing,Function<BlockPos,StructureBlockInfo> read,
                       ToDoubleFunction<StructureBlockInfo> sailPower) {
        var queue=new ArrayDeque<BlockPos>(); var visited=new HashSet<BlockPos>(); queue.add(anchor);
        double power=0;
        while(!queue.isEmpty()) {
            BlockPos position=queue.removeFirst(); if(!visited.add(position)) continue;
            if(visited.size()>4096) throw new IllegalArgumentException("未装配转子超过只读预览预算");
            if(position.distManhattan(anchor)>32) throw new IllegalArgumentException("未装配转子超出预览范围");
            var info=read.apply(position);if(info.state().isAir())continue;
            power+=sailPower.applyAsDouble(new StructureBlockInfo(position.subtract(anchor),info.state(),info.nbt()));
            for(Direction side:Direction.values()) if(side.getAxis()!=facing.getAxis()) queue.add(position.relative(side));
        }
        return power;
    }
}
