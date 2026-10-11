// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.spi;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一个会动的结构此刻的样子（世界坐标）：碰撞盒、整体范围、读全没有、在怎么动；电梯另带整条电梯井。
 *
 * @param id            结构的编号（来源自己认的，同一个结构每次一样），坐这台电梯时按它放开电梯井
 * @param boxes         逐格的真实碰撞盒（门洞留着）；没读全时可以只有一部分
 * @param bounds        整体范围：没读全、或转着扫过时按它
 * @param complete      逐格的碰撞读全了没有
 * @param motion        在怎么动
 * @param shaft         电梯井（最低到最高触点之间、轿厢那么宽的竖直范围）；不是电梯为 null
 * @param carriesPlayer 角色正站在、坐在它上面：脚下的甲板不当墙
 */
public record MovingStructure(String id, List<AABB> boxes, AABB bounds, boolean complete, Motion motion, AABB shaft,
                              boolean carriesPlayer) {

    public MovingStructure {
        Objects.requireNonNull(id, "id");
        boxes = List.copyOf(boxes);
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(motion, "motion");
    }

    /** 结构在怎么动。 */
    public sealed interface Motion {

        /** 停着。 */
        record Still() implements Motion {}

        /**
         * 平移：活塞、龙门、绳索滑轮、矿车装置、列车车厢、电梯轿厢。
         *
         * @param perTick 每刻挪多少格（世界坐标）
         */
        record Sliding(Vec3 perTick) implements Motion {
            public Sliding {
                Objects.requireNonNull(perTick, "perTick");
            }
        }

        /**
         * 转动：风车轴承、机械轴承、时钟轴承、搅拌器、螺旋桨。
         *
         * @param center 转轴中心（世界坐标）
         * @param axis   转轴方向
         * @param radius 最远一格离转轴多远（格）
         */
        record Turning(Vec3 center, Direction.Axis axis, double radius) implements Motion {
            public Turning {
                Objects.requireNonNull(center, "center");
                Objects.requireNonNull(axis, "axis");
            }
        }
    }

    /** 在动（平移或转动）：挡路时先等它过去再绕。 */
    public boolean moving() {
        return !(motion instanceof Motion.Still);
    }
}
