package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;

/**
 * 旧的水平环形站位：例如要求距中心八到十二格，太近向外走、太远向内走，环内估价为零。
 * 高度不参与到达判断；inner 大于等于 outer 时把内圈清为零。
 * 当前没有生产或测试构造调用，不应把这个文件当作现用战斗站位的入口。
 */
public class GoalRing implements Goal {

    public final int x;
    public final int z;
    public final double inner;
    public final double outer;
    private final double costHeuristic;

    /**
     * @param inner 离中心不得近于此;{@code inner >= outer} 时退化成实心球(内沿归零)——
     *              比如大史莱姆够得比玩家还远,那时"无伤"这条带本来就不存在
     */
    public GoalRing(BlockPos centre, double inner, double outer) {
        this.x = centre.getX();
        this.z = centre.getZ();
        this.outer = outer;
        this.inner = inner >= outer ? 0.0 : inner;
        this.costHeuristic = NavSettings.get().costHeuristic;
    }

    private double distanceTo(int px, int pz) {
        double dx = px + 0.5 - (x + 0.5);
        double dz = pz + 0.5 - (z + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        double d = distanceTo(px, pz);
        return d >= inner && d <= outer;
    }

    /**
     * 到<b>带</b>的距离,不是到中心的距离。带里为零;比内沿近就往外算,比外沿远就往里算 ——
     * 两侧都朝带递减,和到达条件同向。
     */
    @Override
    public double heuristic(int px, int py, int pz) {
        double d = distanceTo(px, pz);
        double gap = d < inner ? inner - d : d > outer ? d - outer : 0.0;
        return gap * costHeuristic;
    }

    @Override
    public String toString() {
        return String.format("GoalRing{x=%d,z=%d,band=[%.2f,%.2f]}", x, z, inner, outer);
    }
}
