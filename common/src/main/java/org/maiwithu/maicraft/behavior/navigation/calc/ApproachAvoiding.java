// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 走到一个目标跟前，路上绕开别的敌对生物。到达要两项都成立：走到了，而且脚下这一格不在任何一只的脱身圈里。
 *
 * <p>近战走位就是这样拼的：要打的那只自己也放进威胁场——它的危险半径把角色顶在它够不着的地方，
 * 要去的距离带外沿是角色够得着它的距离，中间那条缝就是拉扯的位置；别的怪各自顶开一圈。
 * 一个合格的格子都没有时也不失败：搜索交出这次找到的最好一段。
 */
public final class ApproachAvoiding implements NavGoal {

    private final NavGoal approach;
    private final ThreatField field;

    public ApproachAvoiding(NavGoal approach, ThreatField field) {
        this.approach = approach;
        this.field = field;
    }

    public NavGoal approach() {
        return approach;
    }

    public ThreatField field() {
        return field;
    }

    @Override
    public boolean isAt(BlockPos feet) {
        return approach.isAt(feet) && field.clearAt(feet.getX(), feet.getY(), feet.getZ());
    }

    // 泅渡到达算不算跟着要去的那个目标走。
    @Override
    public boolean acceptsWaterArrival() {
        return approach.acceptsWaterArrival();
    }

    @Override
    public double heuristic(BlockPos from) {
        return approach.heuristic(from) + field.penaltyAt(from.getX(), from.getY(), from.getZ());
    }

    /** 进度只看走没走近目标：威胁场回答"值不值得走那条路"，不回答"走到哪了"。 */
    @Override
    public double progressHeuristic(BlockPos from) {
        return approach.progressHeuristic(from);
    }

    /** 跟着要去的那个目标走：它一挪动就该重开寻路。 */
    @Override
    public BlockPos center() {
        return approach.center();
    }

    @Override
    public SemanticFingerprint semanticFingerprint() {
        return new SemanticFingerprint("approach_avoiding",
                List.of(approach.semanticFingerprint(), field.penaltyFactor(), field.sortedThreats()));
    }
}
