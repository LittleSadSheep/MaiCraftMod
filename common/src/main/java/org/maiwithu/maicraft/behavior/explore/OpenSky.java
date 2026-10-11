// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

/**
 * 露天判断：脚下列"忽略树叶的最高实体或液体遮挡物"正好在脚底才算露天。
 *
 * <p>树叶不算遮挡，树冠底下照样是露天；未加载的列既不判露天也不判封闭，先走近加载再采样。
 * 明显被埋（顶盖在脚下之上）时，顶盖是液体或非实体方块就无法竖直上掘——硬钻会灌进来
 * 或根本立不住，跳过上掘、记下原因。
 */
public final class OpenSky {

    private OpenSky() {}

    /** 露天判断的结论。 */
    public sealed interface Verdict {
        /** 露天：头顶这一列再没有遮挡物。 */
        record Open() implements Verdict {}

        /**
         * 被埋：顶盖在脚下之上。
         *
         * @param coverY     顶盖所在高度
         * @param coverFluid 顶盖是液体（水、岩浆）：上掘会灌进来，跳过
         * @param coverSolid 顶盖是实体方块：许可动土就沿这一列竖直上掘
         */
        record Buried(int coverY, boolean coverFluid, boolean coverSolid) implements Verdict {}

        /** 这列未加载：既不判露天也不判封闭，先走近加载再采样。 */
        record Unloaded() implements Verdict {}
    }

    /** 按脚下列的样本与脚的高度给结论。 */
    public static Verdict judge(ColumnSample column, int feetY) {
        if (!column.loaded()) {
            return new Verdict.Unloaded();
        }
        if (column.topY() >= feetY) {
            return new Verdict.Buried(column.topY(), column.topFluid(), column.topBlocksMotion());
        }
        // 顶盖在脚下（含正好在脚底）：头顶这一列再没有遮挡，就是露天。
        return new Verdict.Open();
    }
}
