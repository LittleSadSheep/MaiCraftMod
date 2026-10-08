// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 站位判断：围着目标找能落脚的格子，按能到、够得着、看得见、站得稳四项筛，再按走过去的代价排好序。
 * 纯函数：所有世界事实都从只读视图问，所有距离数值都从够得着规则拿，这里只做计算，不动角色。
 *
 * <p>候选不过四项时不丢掉事实：每个被拒的位置带着败在哪一项记进结果，
 * 换站位用它交代试过什么，站位补救从"只差一点"的被拒位置里挑补救对象。
 */
public final class StandSpots {

    /** 脚下落差超过这么多格就不站：再深就是往下跳，不是走过去。类别：玩家常识。 */
    static final double MAX_DROP = 3.0;

    /** 围着目标扫候选站位的水平半径（格）：盖住交互距离属性被装备撑大后的范围。 */
    private static final int SCAN_RADIUS = 5;

    /** 候选落差的扫描：目标上下各几格内找落脚点，床在高台上、实体在坑里都够得到。 */
    private static final int SCAN_VERTICAL = 2;

    /** 床距离的高度差上限：服务端对床横向卡三格、高度只允许差两格。类别：游戏事实。 */
    private static final int BED_VERTICAL_LIMIT = 2;

    /** 一次站位搜索的结果：能用的排好序，被拒的带着失败项。 */
    public record Ranking(List<StandSpot> spots, List<RejectedSpot> rejected) {
    }

    private StandSpots() {}

    /**
     * 找目标周围的候选站位。顺序按走过去的代价从小到大，没有代价信息（寻路没接上）时按远近排。
     * 没加载的格子直接跳过：既不能站，也不算"附近没有站位"的理由。
     */
    public static Ranking find(InteractionTarget target, ReachRules reach,
                               ApproachWorldView world, WalkCost walking, ProtectedCells guarded) {
        List<StandSpot> spots = new ArrayList<>();
        List<RejectedSpot> rejected = new ArrayList<>();
        BlockPos anchor = target.anchorBlock();
        BlockPos current = world.currentFeet();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    review(new BlockPos(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz),
                            target, reach, world, walking, guarded, current, spots, rejected);
                }
            }
        }
        spots.sort(null);
        return new Ranking(spots, rejected);
    }

    /**
     * 到了之后的核对：角色站在给定的位置上，够得着吗、看得见吗、落地了吗。
     * 通过返回 empty；不过时返回最先不过的那一项，用玩家能懂的话说。
     */
    public static Optional<String> checkArrival(InteractionTarget target, ReachRules reach,
                                                ApproachWorldView world, ProtectedCells guarded) {
        BlockPos feet = world.currentFeet();
        if (!world.onGround()) return Optional.of("还没落地");
        String reachFailure = reachFailure(target, reach, feet, world.currentEye());
        if (reachFailure != null) return Optional.of(reachFailure);
        if (!world.visibleFrom(world.currentEye(), target)) return Optional.of("看不见");
        return Optional.empty();
    }

    /** 一个候选位置过一遍四项检查；过得去就带着代价进候选表，过不去就带着失败项进被拒表。 */
    private static void review(BlockPos feet, InteractionTarget target, ReachRules reach,
                               ApproachWorldView world, WalkCost walking, ProtectedCells guarded,
                               BlockPos current, List<StandSpot> spots, List<RejectedSpot> rejected) {
        if (!world.isLoaded(feet)) return;
        String steady = steadyFailure(feet, world, guarded);
        if (steady != null) {
            rejected.add(new RejectedSpot(feet, steady));
            return;
        }
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + reach.eyeHeight(), feet.getZ() + 0.5);
        String tooFar = reachFailure(target, reach, feet, eye);
        if (tooFar != null) {
            rejected.add(new RejectedSpot(feet, tooFar));
            return;
        }
        if (!world.visibleFrom(eye, target)) {
            rejected.add(new RejectedSpot(feet, "看不见"));
            return;
        }
        OptionalDouble cost = walking.from(current, feet);
        // 寻路没接上或走不过去都记成"到不了"；有代价的按代价排，没有的按远近排到末尾。
        if (cost.isEmpty()) {
            rejected.add(new RejectedSpot(feet, "到不了"));
            return;
        }
        spots.add(new StandSpot(feet, cost.getAsDouble()));
    }

    /** 站得稳检查：脚下实心、头顶两格空、落差不大、不在液体里、旁边没岩浆、不踩受保护的格子。 */
    private static String steadyFailure(BlockPos feet, ApproachWorldView world, ProtectedCells guarded) {
        if (guarded.contains(feet)) return "受保护";
        if (!world.solidFloor(feet)) return "脚下悬空";
        if (!world.headroom(feet)) return "头顶没空间";
        if (world.dropBelow(feet) > MAX_DROP) return "落差太深";
        if (world.inFluid(feet)) return "泡在液体里";
        if (world.lavaBeside(feet)) return "旁边有岩浆";
        return null;
    }

    /** 够得着检查：按交互种类取距离规则，返回失败项，够得着返回 null。 */
    private static String reachFailure(InteractionTarget target, ReachRules reach, BlockPos feet, Vec3 eye) {
        return switch (target.kind()) {
            // 方块与实体：从眼睛到目标边缘的直线距离不超属性值，和角色自己伸手判定同源。
            case BLOCK -> eye.distanceTo(nearestPoint(target.bounds(), eye)) > reach.blockRange() ? "够不着" : null;
            case ENTITY -> eye.distanceTo(nearestPoint(target.bounds(), eye)) > reach.entityRange() ? "够不着" : null;
            // 床：服务端按床底面中心分轴卡距离，横向不超过床距离档，高度差不超过两格。
            case BED -> bedOutOfRange(feet, target.block(), reach.bedDistance()) ? "够不着" : null;
        };
    }

    /** 包围盒上离给定点最近的点：把点的三个坐标各自夹进盒子的范围。 */
    private static Vec3 nearestPoint(AABB box, Vec3 point) {
        return new Vec3(
                Math.clamp(point.x, box.minX, box.maxX),
                Math.clamp(point.y, box.minY, box.maxY),
                Math.clamp(point.z, box.minZ, box.maxZ));
    }

    private static boolean bedOutOfRange(BlockPos feet, BlockPos bed, int bedDistance) {
        int dx = Math.abs(feet.getX() - bed.getX());
        int dy = Math.abs(feet.getY() - bed.getY());
        int dz = Math.abs(feet.getZ() - bed.getZ());
        return dx > bedDistance || dz > bedDistance || dy > BED_VERTICAL_LIMIT;
    }
}
