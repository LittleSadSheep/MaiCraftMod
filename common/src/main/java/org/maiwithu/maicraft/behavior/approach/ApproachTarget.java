// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 交互目标：要靠近并对它做事的那一个方块、实体或床。
 * 方块与床用一个格子定位；实体用它的包围盒，因为实体会占不止一格、还会移动。
 *
 * @param kind     交互种类，决定够得着按哪套规则判
 * @param block    方块与床的定位格；实体为 null
 * @param bounds   够得着与看得见按这个包围盒判；方块与床是整格，实体是它此刻的包围盒
 * @param frontYaw 正面的水平朝向角（原版的旋转角，南为 0、往西转）：只站正面那一侧的目标才带，
 *                 其余为 null。告示牌从哪一面点就编辑哪一面，写告示牌只挑正面那一侧的站位，
 *                 站到背面会把字写到背面去。
 */
public record ApproachTarget(InteractionKind kind, BlockPos block, AABB bounds, Double frontYaw) {

    /** 对一个方块做事，例如箱子、工作台；床不用这个，见 {@link #ofBed}。 */
    public static ApproachTarget ofBlock(BlockPos at) {
        return new ApproachTarget(InteractionKind.BLOCK, at, new AABB(at), null);
    }

    /**
     * 对方块上的一个部件做事（例如挂在线缆上的终端面板）：够不够得着、看不看得见都按部件框判，
     * 和瞄准用同一个框，免得站在看得见线缆、看不见面板的地方。
     *
     * @param part 部件框，世界坐标，必须和这一格重叠
     */
    public static ApproachTarget ofBlockPart(BlockPos at, AABB part) {
        if (!new AABB(at).intersects(part)) {
            throw new IllegalArgumentException("部件框不在 " + at.toShortString() + " 这一格里");
        }
        return new ApproachTarget(InteractionKind.BLOCK, at, part, null);
    }

    /**
     * 对一个只站正面那一侧的方块做事（写告示牌）：站位候选只挑正面那一侧，
     * 背面那一侧的按"背面"拒掉。正面的水平朝向角按原版告示牌自己的旋转角给出。
     */
    public static ApproachTarget onFrontSideOf(BlockPos at, double frontYaw) {
        return new ApproachTarget(InteractionKind.BLOCK, at, new AABB(at), frontYaw);
    }

    /** 对一张床做事：给床头或床尾任意一半的格子，距离与视线都按床底面判。 */
    public static ApproachTarget ofBed(BlockPos half) {
        return new ApproachTarget(InteractionKind.BED, half, new AABB(half), null);
    }

    /** 对一个实体做事；包围盒由调用方在取目标的同一刻读出来。 */
    public static ApproachTarget ofEntity(AABB box) {
        BlockPos center = BlockPos.containing(box.getCenter());
        return new ApproachTarget(InteractionKind.ENTITY, center, box, null);
    }

    public ApproachTarget {
        if (kind == null) throw new IllegalArgumentException("交互目标必须有种类");
        if (kind != InteractionKind.ENTITY && block == null) {
            throw new IllegalArgumentException("方块与床的交互目标必须有格子");
        }
        if (kind == InteractionKind.ENTITY && bounds == null) {
            throw new IllegalArgumentException("实体的交互目标必须有包围盒");
        }
        if (frontYaw != null && kind != InteractionKind.BLOCK) {
            throw new IllegalArgumentException("只有方块目标能带正面那一侧的约束");
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
