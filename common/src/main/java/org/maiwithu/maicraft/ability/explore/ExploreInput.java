// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.explore;

import org.maiwithu.maicraft.behavior.explore.ExploreObjective;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次探索的任务输入：解析并校验过的目标、留意清单、扇形与半径，和这次被允许动多少地形。
 * 不可变；运行中会变的进度都在探索任务里。
 *
 * @param objective   探索目标（自由跑图、群系选择器、找平台、回露天）
 * @param interests   途中留意清单（地形要素、结构、方块、实体，已按注册表分好类）
 * @param direction   扇形朝向（八向词）；全向为 null
 * @param angleDegrees 扇形开口角（度），有方向默认 90，全向 360
 * @param minDistance 起找距离（格），有方向默认 16，全向 0
 * @param maxDistance 探索半径（格）
 * @param maxSeconds  最多做多久（秒）；不超过 0 表示不设时限
 * @param permit      这次被允许动多少地形（走行能不能挖垫、被埋能不能上掘）
 */
record ExploreInput(ExploreObjective objective, ExploreObjective.Interests interests,
        Target.Toward direction, int angleDegrees, int minDistance, int maxDistance,
        int maxSeconds, TerrainPermit permit) implements TaskInput {

    @Override
    public String describe() {
        String what = switch (objective.target()) {
            case SURVEY -> "自由跑图看看这一片";
            case BIOME -> "去找群系 " + String.join("、", objective.selectors());
            case FLAT_GROUND -> "朝前找一块能站的平台";
            case SURFACE -> "回露天的地面";
        };
        return what + "，半径 " + maxDistance + " 格";
    }
}
