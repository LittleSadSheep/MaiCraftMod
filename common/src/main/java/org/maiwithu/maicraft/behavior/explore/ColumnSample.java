// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import javax.annotation.Nullable;

/**
 * 一列的观察样本：观察推进逐列抽样读到的游戏事实。
 *
 * <p>顶盖是这一列忽略树叶的最高实体或液体遮挡物（树叶不算遮挡——树冠底下照样是露天）；
 * 未加载的列只知道它未加载，既不判群系也不判露天。
 *
 * @param loaded         这一列是否已加载
 * @param biome          顶盖处的群系注册 ID；未加载为 null
 * @param topY           顶盖所在高度；未加载无意义
 * @param topFluid       顶盖是液体（水、岩浆）；未加载为 false
 * @param topBlocksMotion 顶盖是实体方块（能挡身体、能支撑）；未加载为 false
 * @param topBlockId     顶盖的方块注册 ID；未加载为 null
 */
public record ColumnSample(boolean loaded, @Nullable String biome, int topY,
        boolean topFluid, boolean topBlocksMotion, @Nullable String topBlockId) {

    /** 未加载的一列：既不判群系也不判露天，先走近加载再采样。 */
    public static ColumnSample unloaded() {
        return new ColumnSample(false, null, 0, false, false, null);
    }
}
