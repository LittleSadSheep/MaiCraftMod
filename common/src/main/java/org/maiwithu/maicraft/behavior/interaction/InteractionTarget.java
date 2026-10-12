// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * 这次交互要处理的目标对象：一格方块或一只实体。只有这两种，
 * 交互动作按种类取各自的瞄准点与命中核对规则。
 */
public sealed interface InteractionTarget {

    /**
     * 一格方块；具体点哪个面在瞄准时按当时看得见的面决定。
     *
     * @param part 只点方块上的这个部件（世界坐标的框，例如挂在线缆上的终端面板）；为 null 时整格方块哪里都行。
     *             框正好是整格时按整格方块算：站位挑的是"外形哪里看得见都行"，链条输送机这类外形伸出格外的方块，
     *             瞄准也得认伸出去的那部分，原先只认格子里的，站到了又说一面都看不到
     * @param face 只点方块外形上朝这个方向的表面（放方块时点的那一面）；为 null 时朝哪边都行
     */
    record BlockTarget(BlockPos pos, AABB part, Direction face) implements InteractionTarget {
        public BlockTarget {
            pos = pos.immutable();
            if (part != null && part.equals(new AABB(pos))) part = null;
        }

        /** 整格方块：瞄它看得见的任一部位。 */
        public BlockTarget(BlockPos pos) {
            this(pos, null, null);
        }

        /** 方块上的一个部件。 */
        public BlockTarget(BlockPos pos, AABB part) {
            this(pos, part, null);
        }

        /** 方块外形上朝 face 的那一面：放方块就是点这一面。 */
        public static BlockTarget facing(BlockPos pos, Direction face) {
            return new BlockTarget(pos, null, face);
        }

        @Override public String describe() {
            return face != null ? "方块 " + pos.toShortString() + " 朝 " + face.getName() + " 的表面"
                    : part == null ? "方块 " + pos.toShortString() : "方块 " + pos.toShortString() + " 上的部件";
        }
    }

    /** 一只实体；瞄准它的碰撞箱中心，出手前准星必须真的落在它身上。 */
    record EntityTarget(Entity entity) implements InteractionTarget {
        @Override public String describe() {
            return "实体（编号 " + entity.getId() + "）";
        }
    }

    /** 给日志和现场说明用的一句话。 */
    String describe();
}
