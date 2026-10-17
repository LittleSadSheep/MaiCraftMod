// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 一张图纸编译出来的结果：相对设计原点的计划格，加上给 LLM 看的统计。格还没落到世界里，
 * 盖在哪由 build 的锚点决定。
 *
 * @param cells           相对设计原点的计划格，按 y、z、x 排好
 * @param objectCount     作者写的对象数
 * @param componentCount  组件定义数
 * @param expandedCount   展开后的对象数（含切割体）
 * @param cutterCount     其中的切割体数
 * @param voxelWork       逐格采样的工作量
 * @param overlapCells    不同材料叠加过的格数
 * @param overlapEvents   叠加发生的次数
 * @param overlapExamples 叠加的前几个例子
 */
public record CompiledDesign(List<PlannedCell> cells, int objectCount, int componentCount, int expandedCount, int cutterCount,
                             long voxelWork, int overlapCells, long overlapEvents, List<Overlap> overlapExamples) {

    public CompiledDesign {
        cells = List.copyOf(cells);
        overlapExamples = List.copyOf(overlapExamples);
    }

    /** 一处叠加：哪一格、先后两个对象、先后两种方块。 */
    public record Overlap(BlockPos offset, String previousObject, String incomingObject, String previousBlock, String incomingBlock) {}

    /** 格数。 */
    public int cellCount() {
        return cells.size();
    }

    /** 相对设计原点的包围盒。 */
    public Blueprint.Bounds bounds() {
        return Blueprint.boundsOf(cells);
    }

    /** 从空地建起要每种物品几件。 */
    public Map<String, Integer> materials() {
        return Blueprint.materialsOf(cells);
    }
}
