package org.maiwithu.maicraft.core.pathing.moves;

/**
 * 旧动作费用计算临时填写的落点和费用，可反复清空再用。清空后的费用是不可走，不把默认的零坐标当作有效结果。
 */
public final class MutableMoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;

    public MutableMoveResult() {
        reset();
    }

    /** 重置为"不可行":坐标清零,成本置 INF。 */
    public void reset() {
        x = 0;
        y = 0;
        z = 0;
        cost = ActionCosts.COST_INF;
    }
}
