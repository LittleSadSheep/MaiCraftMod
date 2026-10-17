// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 读一片范围内格子的接缝：机器查看要"这一片每格是什么"，格子数上万，一刻读不完，
 * 任务每刻问一轮"这一轮读出了什么、扫完没有"。只读，不动角色、不改方块。
 *
 * <p>实现在读的现场裁决世界换没换；任务拿到的每一轮都带着"扫完没有"，
 * 没扫完的部分不能当成"没有"。实现留在启动一侧接真实客户端，测试用替身摆格子。
 */
interface ReadsMachineArea {

    /**
     * 读一轮：给出这一轮读出的非空格子与覆盖情况。同一轮扫描（范围没变）接着上一轮的进度往下读；
     * 范围变了就重新开始。
     *
     * @param dimension 扫描所在的维度；换了维度这一轮作废
     * @param center    球形范围的中心
     * @param radius    半径，单位格
     */
    Round scan(String dimension, BlockPos center, int radius);

    /**
     * 一轮读取的收成。
     *
     * @param cells         这一刻读出的非空格子，附带方块状态与"有没有方块实体"
     * @param unloadedCells 到此刻为止读到的未加载格数：这些格不知道是什么，不按空气算
     * @param complete      愿意读的范围读完了没有
     * @param worldChanged  读的期间换了世界（或角色离开了世界）：这一轮作废，任务如实结束
     */
    record Round(List<Cell> cells, int unloadedCells, boolean complete, boolean worldChanged) {

        public Round {
            cells = List.copyOf(cells);
        }

        /** 没有格子的空轮：没在世界里时用它。 */
        static Round empty(boolean worldChanged) {
            return new Round(List.of(), 0, false, worldChanged);
        }
    }

    /** 一格读出的事实：位置、方块状态、有没有方块实体（看着像机器的格才有方块实体）。 */
    record Cell(BlockPos pos, BlockState state, boolean hasBlockEntity) {
    }
}
