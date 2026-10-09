// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.core.BlockPos;
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
     * @param part 只点方块上的这个部件（世界坐标的框，例如挂在线缆上的终端面板）；为 null 时整格方块哪里都行
     */
    record BlockTarget(BlockPos pos, AABB part) implements InteractionTarget {
        public BlockTarget {
            pos = pos.immutable();
        }

        /** 整格方块：瞄它看得见的任一部位。 */
        public BlockTarget(BlockPos pos) {
            this(pos, null);
        }

        @Override public String describe() {
            return part == null ? "方块 " + pos.toShortString() : "方块 " + pos.toShortString() + " 上的部件";
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
