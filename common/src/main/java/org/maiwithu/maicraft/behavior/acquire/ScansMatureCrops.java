// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 扫成熟作物的只读接缝：以一处为圆心，找熟了可以收的作物。只有熟了的才算——
 * 没熟的被踩掉就白长了；成熟与否按方块自己的状态判断，实现在游戏接口层。
 */
public interface ScansMatureCrops {

    /**
     * 半径内熟了的、收了能拿到想要的东西的作物，由近及远。
     *
     * @param wanted       想要的东西，例如 minecraft:wheat；按作物的产出对上
     * @param center       从哪里找起
     * @param radiusBlocks 半径，单位格
     */
    List<CropSpot> mature(WantedItem wanted, WorldPosition center, int radiusBlocks);
}
