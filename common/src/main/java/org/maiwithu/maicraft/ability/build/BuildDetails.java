// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.BuildingLayout;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 施工能力的结果细节：施工引擎给的每格结局、问题、用料、临时方块去向，加上这一栋的档案、整栋比对和（改建时的）布局。
 * 没有的字段不写：单格放在档案范围外时没有档案，也不做整栋比对。
 *
 * @param anchor              锚点
 * @param boundsMin           这次施工范围的最小角
 * @param boundsMax           这次施工范围的最大角
 * @param cells               每种结局几格
 * @param problems            没做成的格按原因分组
 * @param materialsUsed       用掉的材料
 * @param temporaryBlocksLeft 没收回的临时方块
 * @param fixturesSkipped     结构文件里没装的摆设实体数
 * @param archive             这一栋的档案：名字、锚点、朝向、图纸版本；存不进去时写一句
 * @param check               整栋比对：整栋蓝图对现场逐格比，没对上的每一格都列
 * @param layout              改建后这一栋的布局（楼层、门、门洞、窗、床、上下楼、设施、屋里空地）
 */
public record BuildDetails(WorldPosition anchor, BlockPos boundsMin, BlockPos boundsMax, Map<String, Integer> cells,
                           List<ConstructionDetails.ProblemGroup> problems, Map<String, Integer> materialsUsed,
                           List<BlockPos> temporaryBlocksLeft, Integer fixturesSkipped, Archive archive,
                           BlueprintCheck.Report check, BuildingLayout layout) implements ResultDetails {

    /**
     * 一栋的档案在结果里的样子。
     *
     * @param name     档案名，之后 target 写它就是改这一栋
     * @param anchor   锚点
     * @param rotation 朝向
     * @param designId 照哪一版图纸；逐格或结构文件盖的没有
     * @param problem  档案存不进去时的一句话；存好了不写
     */
    public record Archive(String name, WorldPosition anchor, int rotation, String designId, String problem) {}

    /** 施工引擎的细节原样带上，再加档案、整栋比对与布局。 */
    static BuildDetails of(ConstructionDetails construction, Archive archive, BlueprintCheck.Report check, BuildingLayout layout) {
        return new BuildDetails(construction.anchor(), construction.boundsMin(), construction.boundsMax(), construction.cells(),
                construction.problems(), construction.materialsUsed(), construction.temporaryBlocksLeft(),
                construction.fixturesSkipped(), archive, check, layout);
    }
}
