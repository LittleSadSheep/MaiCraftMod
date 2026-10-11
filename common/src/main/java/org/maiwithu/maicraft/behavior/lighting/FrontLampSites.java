// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;

/**
 * 随行插火的灯位判断：在眼前约正前方 78° 视野内、站姿够得着的墙面或地面挑一处插火把的地方。
 * 纯函数：现场从第一人称现场接缝读，测试用替身摆场景。斜向走的时候视野跟着镜头，正前方照样有灯位。
 *
 * <p>资格：目标格是空气或可替换的草丛，不覆盖已有方块；不挖洞、不垫脚——目标格的高度够不着就直接放弃；
 * 箱子、机器、工作台这类会开界面的方块不作灯座；支撑格在保护里就当看不见这个地方。
 */
public final class FrontLampSites {

    /** 视野半角：正前方 78° 视野，两边各 39 度。 */
    private static final double CONE_HALF_RADIANS = Math.toRadians(39);

    /** 随行插火把够得着的距离：按站姿伸手可及算。 */
    public static final double REACH = 4.5;

    private FrontLampSites() {}

    /** 一处选中的灯位：火把落在 lamp 格，支撑是 support 格的 supportFace 那一面。 */
    public record LampSpot(BlockPos lamp, BlockState state, BlockPos support, Direction supportFace) {}

    /**
     * 在眼前挑一处灯位；一个都找不到为空。
     *
     * @param scene       第一人称现场：眼睛、视线、方块、可见面
     * @param opensScreen 这一格的方块会不会右键开界面（箱子、机器、工作台不作灯座）
     * @param protectedAt 这一格是否不许动；读不到保护时按受保护对待，不出手
     * @param eye         眼睛位置
     * @param view        视线方向
     * @param torches     立式与贴墙的火把落法
     */
    public static Optional<LampSpot> choose(FirstPersonScene scene, Predicate<BlockPos> opensScreen,
                                            Predicate<BlockPos> protectedAt, Vec3 eye, Vec3 view, LampItem torches) {
        BlockPos feet = BlockPos.containing(eye.x, eye.y - 1.62, eye.z);
        LampSpot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos at = feet.offset(dx, dy, dz);
                    // 自己的脚下与头上不放灯：放下去卡住自己。
                    if (at.equals(feet) || at.equals(feet.above())) continue;
                    double distance = horizontalDistance(eye, at);
                    if (distance > REACH || distance >= bestDistance) continue;
                    if (outsideCone(eye, view, at)) continue;
                    LampSpot spot = spotAt(at, scene, opensScreen, protectedAt, torches);
                    if (spot != null) {
                        best = spot;
                        bestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** 一格能不能插：立式看脚下，贴墙看墙面；目标格与支撑格都要干净、不开放界面、不在保护里。 */
    private static LampSpot spotAt(BlockPos at, FirstPersonScene scene, Predicate<BlockPos> opensScreen,
                                   Predicate<BlockPos> protectedAt, LampItem torches) {
        if (!scene.isLoaded(at)) return null;
        BlockState state = scene.blockAt(at);
        if (state == null || !SampleRules.passable(state) || SampleRules.sensitive(state)) return null;
        // 立式：脚下那格是实打实的方块（不是空气草丛）、不作灯座、不在保护里，那一面看得见。
        BlockState below = scene.blockAt(at.below());
        if (torches.standing() != null && below != null && !SampleRules.passable(below) && !SampleRules.sensitive(below)
                && !opensScreen.test(at.below()) && !protectedAt.test(at.below())
                && scene.visibleHit(at.below()) != null) {
            return new LampSpot(at, standing(torches.standing()), at.below(), Direction.UP);
        }
        if (torches.wall() == null) return null;
        for (Direction direction : HORIZONTAL) {
            BlockPos support = at.offset(direction.getStepX(), 0, direction.getStepZ());
            BlockState wall = scene.blockAt(support);
            if (wall == null || SampleRules.passable(wall) || SampleRules.sensitive(wall)) continue;
            if (opensScreen.test(support) || protectedAt.test(support)) continue;
            if (scene.visibleHit(support) == null) continue;
            // 贴墙火把指向离开墙的那一边（direction 从灯位指向墙）。
            return new LampSpot(at, torches.wall().setValue(WallTorchBlock.FACING, direction.getOpposite()),
                    support, direction.getOpposite());
        }
        return null;
    }

    /** 立式落法：灯笼不选悬挂态。 */
    private static BlockState standing(BlockState torch) {
        return torch.hasProperty(LanternBlock.HANGING)
                ? torch.setValue(LanternBlock.HANGING, false) : torch;
    }

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** 目标格在不在正前方的视野锥里（按水平方向算）。 */
    private static boolean outsideCone(Vec3 eye, Vec3 view, BlockPos at) {
        double toX = at.getX() + 0.5 - eye.x;
        double toZ = at.getZ() + 0.5 - eye.z;
        double length = Math.sqrt(toX * toX + toZ * toZ);
        if (length < 0.5) return false;
        double viewX = view.x;
        double viewZ = view.z;
        double viewLength = Math.sqrt(viewX * viewX + viewZ * viewZ);
        if (viewLength < 0.0001) return false;
        double cos = (toX * viewX + toZ * viewZ) / (length * viewLength);
        return cos < Math.cos(CONE_HALF_RADIANS);
    }

    private static double horizontalDistance(Vec3 eye, BlockPos at) {
        double dx = at.getX() + 0.5 - eye.x;
        double dz = at.getZ() + 0.5 - eye.z;
        double dy = at.getY() + 0.5 - eye.y;
        return Math.sqrt(dx * dx + dz * dz + dy * dy);
    }
}
