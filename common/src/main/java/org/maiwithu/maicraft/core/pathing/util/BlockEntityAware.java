package org.maiwithu.maicraft.core.pathing.util;

import net.minecraft.core.BlockPos;

/**
 * 让冻结世界视图只回答“这一格是否有方块实体”，不用把箱子等真实、可变化的实体对象带进旧后台搜索。
 */
public interface BlockEntityAware {
    boolean hasBlockEntity(BlockPos pos);
}
