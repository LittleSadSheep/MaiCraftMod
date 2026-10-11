// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;

/**
 * 会动的结构换成走不得的地方，纯函数：
 * 读全了的停着的结构按逐格的真实碰撞（门洞留着）；读不全的按整体范围；
 * 转着的按整个扫过的圆盘（桨叶下一刻就转到那里）；平移的在当前碰撞上沿运动方向再延长两秒的路程（别走到它要去的地方）；
 * 电梯另把整条电梯井算上，这一段就是坐这台电梯时除外；角色正站在、坐在的那个结构不算（脚下的甲板不当墙）。
 */
public final class ObstacleShapes {

    /** 平移的结构往前延长多少刻的路程（2 秒）。 */
    static final int LEAD_TICKS = 40;
    /** 一个结构逐格的碰撞盒超过这么多就按整体范围：大结构逐格比太慢，整体绕开更稳妥。 */
    static final int MAX_BOXES_PER_STRUCTURE = 2048;

    private ObstacleShapes() {}

    /**
     * @param riding 这一段正坐着的结构（电梯）：它的轿厢和电梯井都不挡
     */
    public static List<AABB> blocked(MovingStructure structure, Set<String> riding) {
        if (structure.carriesPlayer() || riding.contains(structure.id())) return List.of();
        List<AABB> result = new ArrayList<>();
        switch (structure.motion()) {
            case MovingStructure.Motion.Turning turning -> result.add(sweptDisc(turning, structure.bounds()));
            case MovingStructure.Motion.Sliding sliding -> {
                Vec3 lead = sliding.perTick().scale(LEAD_TICKS);
                for (AABB box : collision(structure)) result.add(box.expandTowards(lead));
            }
            case MovingStructure.Motion.Still still -> result.addAll(collision(structure));
        }
        if (structure.shaft() != null) result.add(structure.shaft());
        return result;
    }

    /** 读全了、又不大，按逐格碰撞；否则按整体范围。 */
    static List<AABB> collision(MovingStructure structure) {
        boolean detailed = structure.complete() && !structure.boxes().isEmpty()
                && structure.boxes().size() <= MAX_BOXES_PER_STRUCTURE;
        return detailed ? structure.boxes() : List.of(structure.bounds());
    }

    /** 转着的整个扫盘：垂直于转轴的两个方向上以转轴为中心、最远一格为半径，沿转轴取结构本身的厚度。 */
    static AABB sweptDisc(MovingStructure.Motion.Turning turning, AABB bounds) {
        Vec3 c = turning.center();
        double r = turning.radius();
        Direction.Axis axis = turning.axis();
        double minX = axis == Direction.Axis.X ? bounds.minX : c.x - r;
        double maxX = axis == Direction.Axis.X ? bounds.maxX : c.x + r;
        double minY = axis == Direction.Axis.Y ? bounds.minY : c.y - r;
        double maxY = axis == Direction.Axis.Y ? bounds.maxY : c.y + r;
        double minZ = axis == Direction.Axis.Z ? bounds.minZ : c.z - r;
        double maxZ = axis == Direction.Axis.Z ? bounds.maxZ : c.z + r;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
