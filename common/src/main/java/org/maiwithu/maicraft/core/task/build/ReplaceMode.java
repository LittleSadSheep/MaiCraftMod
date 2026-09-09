package org.maiwithu.maicraft.core.task.build;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 决定目标格已有方块时，是否允许替换，以及是否处理蓝图中的空气。
 * 草等可直接覆盖的方块由 canBeReplaced 判断；液体、保护区域和危险方块还要由调用者另外检查。
 */
public enum ReplaceMode {

    /** 只往空地和软方块上放:既有建筑一格都不碰,也不清空。 */
    DONT_REPLACE,

    /** 同上,外加"要放的是完整实心块时也可以顶掉"——骨架能压过去,细节不行。 */
    REPLACE_SOLID,

    /** 挡路的一律顶掉,但不清空:图纸里的空气格当作"不管这一格"。 */
    REPLACE_ANY,

    /** 连该空的地方也清掉——图纸里的空气格是"把这里挖空"的指令。 */
    REPLACE_EMPTY;

    /**
     * 这一格现在该不该动手。
     *
     * @param current 世界里现在是什么
     * @param desired 图纸要它变成什么(空气 = 清空)
     */
    public boolean allows(BlockState current, BlockState desired) {
        // null 也按清空处理；只有 REPLACE_EMPTY 允许清空，其余模式直接跳过空气目标。
        boolean clearing = desired == null || desired.isAir();
        if (clearing) {
            return this == REPLACE_EMPTY;   // 只有最高那档做清场
        }
        if (this == REPLACE_ANY || this == REPLACE_EMPTY) {
            return true;
        }
        boolean soft = current.isAir() || current.canBeReplaced();
        if (soft) {
            return true;
        }
        // 到这里当前方块已经不是可直接覆盖的；REPLACE_SOLID 还要求新目标占满整个方块碰撞空间。
        return this == REPLACE_SOLID && desired.isCollisionShapeFullBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO);
    }
}
