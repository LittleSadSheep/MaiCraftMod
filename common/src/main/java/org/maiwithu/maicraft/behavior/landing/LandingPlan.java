// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 一个落地办法：用哪一种、人落在哪一格（feet）、东西放进哪一格（cell）、点哪一格的哪一面，以及是不是现成的。
 * 计划自己不放东西；放之前还要把东西拿到手上、对准、等游戏确认。
 *
 * @param hitPoint 放水时实际要点的那一点（地面上露出来的那块）；按格子中心点就行时为 null
 */
public record LandingPlan(LandingAid aid, BlockPos feet, BlockPos cell, BlockPos clicked, Direction face,
                          boolean existing, Vec3 hitPoint) {

    public LandingPlan {
        feet = feet.immutable();
        cell = cell.immutable();
        clicked = clicked.immutable();
    }

    public LandingPlan(LandingAid aid, BlockPos feet, BlockPos cell, BlockPos clicked, Direction face,
                       boolean existing) {
        this(aid, feet, cell, clicked, face, existing, null);
    }

    /** 要对准的那一点：给了实际点位用它，否则点那一面的中心。 */
    public Vec3 aimPoint() {
        return hitPoint != null ? hitPoint
                : Vec3.atCenterOf(clicked).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
    }

    /** 被点的那一面有多高（含植物、半砖这类不满格的形状）；算水桶什么时候够得着用。 */
    public double placementHeight(BlockGetter view) {
        if (hitPoint != null) return hitPoint.y;
        var shape = view.getBlockState(clicked).getShape(view, clicked);
        if (shape.isEmpty()) return Double.NaN;
        return clicked.getY() + switch (face) {
            case UP -> shape.max(Direction.Axis.Y);
            case DOWN -> shape.min(Direction.Axis.Y);
            default -> shape.bounds().getCenter().y;
        };
    }

    /** 落在这上面能不能活：只有干草要算（减到五分之一后的伤害）；其余办法免伤。 */
    public boolean survives(FallDamage damage, double playerY, boolean includeAccumulated) {
        return aid != LandingAid.HAY || damage.survives(Math.max(0, playerY - cell.getY() - 1),
                FallDamage.Landing.of(aid.block().defaultBlockState()), includeAccumulated);
    }

    /**
     * 能不能收回：只收本次确认自己放下的、状态和放下时一模一样、没被保护的；
     * 长大了、被换了、后来被划进保护的都不收，免得收走别人的东西。
     */
    public static boolean canRecover(boolean confirmedOwnPlacement, BlockState recorded, BlockState current,
                                     boolean protectedCell) {
        return confirmedOwnPlacement && recorded != null && !recorded.isAir()
                && !protectedCell && recorded.equals(current);
    }
}
