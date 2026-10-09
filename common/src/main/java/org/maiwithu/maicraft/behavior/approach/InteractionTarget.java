// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 交互目标：要靠近并对它做事的那一个方块、实体或床。
 * 方块与床用一个格子定位；实体用它的包围盒，因为实体会占不止一格、还会移动。
 */
public record InteractionTarget(InteractionKind kind, BlockPos block, AABB bounds) {

    /** 对一个方块做事，例如箱子、工作台；床不用这个，见 {@link #ofBed}。 */
    public static InteractionTarget ofBlock(BlockPos at) {
        return new InteractionTarget(InteractionKind.BLOCK, at, new AABB(at));
    }

    /**
     * 对方块上的一个部件做事（例如挂在线缆上的终端面板）：够不够得着、看不看得见都按部件框判，
     * 和瞄准用同一个框，免得站在看得见线缆、看不见面板的地方。
     *
     * @param part 部件框，世界坐标，必须和这一格重叠
     */
    public static InteractionTarget ofBlockPart(BlockPos at, AABB part) {
        if (!new AABB(at).intersects(part)) {
            throw new IllegalArgumentException("部件框不在 " + at.toShortString() + " 这一格里");
        }
        return new InteractionTarget(InteractionKind.BLOCK, at, part);
    }

    /** 对一张床做事：给床头或床尾任意一半的格子，距离与视线都按床底面判。 */
    public static InteractionTarget ofBed(BlockPos half) {
        return new InteractionTarget(InteractionKind.BED, half, new AABB(half));
    }

    /** 对一个实体做事；包围盒由调用方在取目标的同一刻读出来。 */
    public static InteractionTarget ofEntity(AABB box) {
        BlockPos center = BlockPos.containing(box.getCenter());
        return new InteractionTarget(InteractionKind.ENTITY, center, box);
    }

    public InteractionTarget {
        if (kind == null) throw new IllegalArgumentException("交互目标必须有种类");
        if (kind != InteractionKind.ENTITY && block == null) {
            throw new IllegalArgumentException("方块与床的交互目标必须有格子");
        }
        if (kind == InteractionKind.ENTITY && bounds == null) {
            throw new IllegalArgumentException("实体的交互目标必须有包围盒");
        }
    }

    /** 目标所占的代表格：方块与床是它自己的格子，实体取包围盒中心所在格，候选站位围着它找。 */
    public BlockPos anchorBlock() {
        return block;
    }

    /** 目标的中心点：实体取包围盒中心，方块与床取格子中心。 */
    public Vec3 center() {
        return bounds == null ? block.getCenter() : bounds.getCenter();
    }
}
