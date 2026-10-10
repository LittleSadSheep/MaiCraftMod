// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 施工的结果细节：锚点与包围盒、每种结局几格、没做成的格按原因分组（原因、几格、全部坐标）、
 * 用掉的材料、没收回的临时方块。默认完整交付，不在中间层按固定数量删。
 *
 * @param anchor              锚点
 * @param boundsMin           包围盒最小角
 * @param boundsMax           包围盒最大角
 * @param cells               每种结局几格，键是结局的小写名
 * @param problems            没做成的格按原因分组
 * @param materialsUsed       用掉的材料（物品 → 件数）
 * @param temporaryBlocksLeft 没收回的临时方块
 */
public record ConstructionDetails(
        WorldPosition anchor,
        BlockPos boundsMin,
        BlockPos boundsMax,
        Map<String, Integer> cells,
        List<ProblemGroup> problems,
        Map<String, Integer> materialsUsed,
        List<BlockPos> temporaryBlocksLeft) implements ResultDetails {

    public ConstructionDetails {
        cells = Map.copyOf(cells);
        problems = List.copyOf(problems);
        materialsUsed = Map.copyOf(materialsUsed);
        temporaryBlocksLeft = List.copyOf(temporaryBlocksLeft);
    }

    /** 一组没做成的格：原因、几格、全部坐标。 */
    public record ProblemGroup(String reason, int count, List<BlockPos> positions) {
        public ProblemGroup {
            positions = List.copyOf(positions);
        }
    }
}
