// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行目的地：解析完成后、交给走到的一段导航要求——去哪个位置、高度核实过没有、容差多少格。
 *
 * <p>高度纪律在这里落地：坐标只给了 x/z（或方向走多远）时高度没核实，
 * 目的地只约束那一柱列，到了附近再找能站的格子；不把没核实的高度直接交给寻路，
 * 免得寻路对着永远到不了的高度空转。名字记得的地点与观察编号都带当时亲眼确认过的高度，算核实过。
 *
 * @param position        目的地位置；高度没核实时 y 无意义，只为记录调用方给的值
 * @param heightConfirmed 高度是否核实过（给全了 y、地标或观察编号解析出来的）
 * @param radius          到达容差，单位格；0 表示要站进那一格
 */
public record TravelDestination(WorldPosition position, boolean heightConfirmed, double radius) {

    public TravelDestination {
        if (radius < 0) {
            throw new IllegalArgumentException("到达容差不能为负：" + radius);
        }
    }

    /** 高度核实过的目的地：给全了 y 的坐标、记得的地点、观察编号指向的东西。 */
    public static TravelDestination confirmed(WorldPosition position, double radius) {
        return new TravelDestination(position, true, radius);
    }

    /** 高度没核实的目的地：只有 x/z（或方向走多远算出来的落点），先走到那一柱列。 */
    public static TravelDestination anyHeight(int x, int z, double radius, String dimension) {
        return new TravelDestination(new WorldPosition(x, 0, z, dimension), false, radius);
    }

    /** 编译成走到用的导航目标：高度核实过按位置与容差走，没核实过只走到那一柱列。 */
    public NavGoal navGoal() {
        if (!heightConfirmed) {
            return NavGoal.column(position.x(), position.z(), radius);
        }
        BlockPos cell = new BlockPos(position.x(), position.y(), position.z());
        return radius <= 0 ? NavGoal.exact(cell) : NavGoal.near(cell, radius);
    }

    /** 量剩余距离用的参照点：柱列目的地取给定的 x/z（高度按脚下的算）。 */
    public BlockPos center() {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    /** 给日志与结果的一句话，例如"坐标 (120, 64, -80) 附近 2 格"。 */
    public String describe() {
        String place = heightConfirmed
                ? "坐标 (" + position.x() + ", " + position.y() + ", " + position.z() + ")"
                : "（" + position.x() + ", " + position.z() + "）那一列";
        return place + (radius > 0 ? " 附近 " + (int) Math.ceil(radius) + " 格" : "");
    }
}
