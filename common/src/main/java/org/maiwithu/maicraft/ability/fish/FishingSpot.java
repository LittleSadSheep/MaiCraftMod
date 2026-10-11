// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 钓位判断：一个干站位加一个能抛到的开阔水面的组合，纯函数、离线可测。
 *
 * <p>干站位是两格无流体、能穿过、脚下能站的格子；可抛落点是水源最上层且上方无碰撞的格子，
 * 流动的水和顶上有实物挡着的水都不算。落点只认水平四到十格内的候选，偏好约六格远、周围水面多的；
 * 弹道按抛竿初速逐刻模拟，中途碰到任何方块就换下一个候选。已经站在干岸上时只看从原地
 * 能不能抛到——找不到就如实说原地无落点，不能说成附近没有水。
 */
final class FishingSpot {

    /** 站位搜索的水平半径与上下范围。 */
    static final int STANCE_RADIUS = 12;
    static final int STANCE_VERTICAL = 4;
    /** 落点的水平窗口（四到十格）与上下范围。 */
    static final int LANDING_RADIUS = 10;
    static final int LANDING_VERTICAL = 4;
    static final double MIN_LANDING_DISTANCE = 4.0;
    static final double PREFERRED_LANDING_DISTANCE = 6.0;
    /** 圈内最多核对多少个站位候选：圈很大时保住每刻预算。 */
    static final int MAX_STANCE_CHECKS = 256;
    /** 搜索站位时假想的眼睛高度：弹道与瞄准都从站上去之后的眼光出发。 */
    static final double STAND_EYE_HEIGHT = 1.62;
    /** 无水判定比站位搜得更深一截：干岸下方与低洼处的水面也要扫到，圈没扫够深就不能说"圈内无可钓水面"。 */
    static final int WATER_PROBE_VERTICAL = 8;

    /** 钓位判断的世界读缝：一格能不能站、是不是可抛的水面、受不受保护、一段射线路径碰不碰方块。 */
    interface World {
        /** 这一格所在的区块加载了没有；没加载的格不参与判断。 */
        boolean isLoaded(BlockPos pos);

        /** 这一格是不是干站位：两格无流体、能穿过、脚下能站。 */
        boolean dryStance(BlockPos pos);

        /** 这一格是不是可抛的水面：水源最上层、上方无碰撞；流动的水与顶上有实物的不算。 */
        boolean castableWater(BlockPos pos);

        /** 这一格受不受保护（不许踩）：受保护的站位不选。 */
        boolean protectedCell(BlockPos pos);

        /** 两个世界坐标之间的射线路径会不会碰到方块（弹道模拟的碰撞查询）。 */
        boolean terrainBlocked(Vec3 from, Vec3 to);
    }

    /** 搜索的结论：找到一组钓位，或者三种找不到（各自的说法都带扫描范围）。 */
    sealed interface Search {
        record Found(BlockPos stance, BlockPos landing) implements Search {}

        /** 已站在干岸，但从原地找不到能抛到的落点。 */
        record NoLandingFromHere(BlockPos stance, int radius, int vertical) implements Search {}

        /** 圈里有可钓的水面，但没有一处"干站位加落点"的组合。 */
        record NoStanceWithWater(int radius, int vertical, int checked) implements Search {}

        /** 圈内没看见可钓的水面；有界扫描不构成圈外无水的证据。 */
        record NoVisibleWater(int radius, int vertical) implements Search {}
    }

    private FishingSpot() {}

    /**
     * 找一组钓位：站在没受保护的干岸上就只找原地落点，否则在圈内搜"站位加落点"组合，就近取。
     * 试过失败的水面与站位从各自的排除集里剔除，同一处不撞两次。
     */
    static Search search(World world, BlockPos feet, Set<BlockPos> excludedStances,
                         Set<BlockPos> excludedLandings) {
        // 受保护的格不站：脚下就算站得稳也换圈内别的干站位组合。
        if (world.dryStance(feet) && !world.protectedCell(feet)) {
            Optional<BlockPos> landing = landingFrom(world, feet, excludedLandings);
            if (landing.isPresent()) return new Search.Found(feet, landing.get());
            // 原地抛不出去：圈内还有别的水面才算"原地的问题"，整圈没水是另一种说法。
            return anyVisibleWater(world, feet)
                    ? new Search.NoLandingFromHere(feet, LANDING_RADIUS, LANDING_VERTICAL)
                    : new Search.NoVisibleWater(STANCE_RADIUS, WATER_PROBE_VERTICAL);
        }
        List<BlockPos> stances = new ArrayList<>();
        int checked = 0;
        boolean sawWater = false;
        for (int dy = -STANCE_VERTICAL; dy <= STANCE_VERTICAL; dy++) {
            for (int dx = -STANCE_RADIUS; dx <= STANCE_RADIUS && checked < MAX_STANCE_CHECKS; dx++) {
                for (int dz = -STANCE_RADIUS; dz <= STANCE_RADIUS && checked < MAX_STANCE_CHECKS; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!world.isLoaded(candidate)) continue;
                    // 水面单独看：圈内连水面都没有与"有水但没有站位"是两种结论，话术不同。
                    if (waterCandidate(world, candidate, excludedLandings)) sawWater = true;
                    if (!world.dryStance(candidate) || world.protectedCell(candidate)) continue;
                    if (excludedStances.contains(candidate)) continue;
                    checked++;
                    stances.add(candidate.immutable());
                }
            }
        }
        if (!sawWater) return new Search.NoVisibleWater(STANCE_RADIUS, WATER_PROBE_VERTICAL);
        if (stances.isEmpty()) return new Search.NoStanceWithWater(STANCE_RADIUS, STANCE_VERTICAL, checked);
        // 就近取：先看离得最近的几个站位能不能抛，最多核对 256 个候选。
        stances.sort(Comparator.comparingDouble(at -> at.distSqr(feet)));
        for (BlockPos stance : stances.subList(0, Math.min(stances.size(), MAX_STANCE_CHECKS))) {
            Optional<BlockPos> landing = landingFrom(world, stance, excludedLandings);
            if (landing.isPresent()) return new Search.Found(stance, landing.get());
        }
        return new Search.NoStanceWithWater(STANCE_RADIUS, STANCE_VERTICAL, checked);
    }

    /**
     * 从一个站位找落点：水平四到十格、上下四格内的可抛水面，按"离约六格近、周围水面多、高低差小"排序，
     * 逐个用弹道模拟核对，被方块挡住就换下一个。
     */
    static Optional<BlockPos> landingFrom(World world, BlockPos stance, Set<BlockPos> excludedLandings) {
        Vec3 eye = new Vec3(stance.getX() + 0.5, stance.getY() + STAND_EYE_HEIGHT, stance.getZ() + 0.5);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -LANDING_VERTICAL; dy <= LANDING_VERTICAL; dy++) {
            for (int dx = -LANDING_RADIUS; dx <= LANDING_RADIUS; dx++) {
                for (int dz = -LANDING_RADIUS; dz <= LANDING_RADIUS; dz++) {
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    if (horizontal < MIN_LANDING_DISTANCE || horizontal > LANDING_RADIUS) continue;
                    BlockPos candidate = stance.offset(dx, dy, dz);
                    if (excludedLandings.contains(candidate)) continue;
                    if (waterCandidate(world, candidate, excludedLandings)) candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> landingScore(world, stance, candidate)));
        for (BlockPos candidate : candidates) {
            if (!CastTrajectory.clearsTerrain(eye, candidate, world::terrainBlocked)) continue;
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** 这一格现在算不算一个可用的落点候选：已加载、是可抛水面、且没进排除集。 */
    private static boolean waterCandidate(World world, BlockPos pos, Set<BlockPos> excludedLandings) {
        return world.isLoaded(pos) && !excludedLandings.contains(pos) && world.castableWater(pos);
    }

    /** 站位搜索圈（垂直探得更深）里还有没有一处客观存在的水面：区分"原地抛不出去"与"圈里没水"。 */
    private static boolean anyVisibleWater(World world, BlockPos from) {
        for (int dy = -WATER_PROBE_VERTICAL; dy <= WATER_PROBE_VERTICAL; dy++) {
            for (int dx = -STANCE_RADIUS; dx <= STANCE_RADIUS; dx++) {
                for (int dz = -STANCE_RADIUS; dz <= STANCE_RADIUS; dz++) {
                    BlockPos at = from.offset(dx, dy, dz);
                    if (world.isLoaded(at) && world.castableWater(at)) return true;
                }
            }
        }
        return false;
    }

    /** 找不到钓位的三种说法各自分开，都带扫描范围；原地无落点不能说成附近没有水。 */
    static String messageOf(Search search) {
        return switch (search) {
            case Search.NoLandingFromHere no -> "站在干岸上，但从原地（水平 " + no.radius()
                    + " 格、上下 " + no.vertical() + " 格）找不到能抛到的开阔水面；这里未必没有水，只是原地抛不出去";
            case Search.NoStanceWithWater no -> "半径 " + no.radius() + " 格、上下 " + no.vertical()
                    + " 格里有可钓的水面，但没有一处「干站位加落点」的组合（核对过 " + no.checked() + " 个候选）";
            case Search.NoVisibleWater no -> "半径 " + no.radius() + " 格、垂直向下 " + no.vertical()
                    + " 格内没看见可钓的水面；有界扫描不构成圈外没有水的证据";
            case Search.Found found -> "找到了钓位";
        };
    }

    /** 落点的偏好分：约六格远、高低差小、周围水面多的排前面（分越低越好）。 */
    private static double landingScore(World world, BlockPos stance, BlockPos candidate) {
        double dx = candidate.getX() - stance.getX();
        double dz = candidate.getZ() - stance.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return Math.abs(horizontal - PREFERRED_LANDING_DISTANCE)
                + Math.abs(candidate.getY() - stance.getY()) * 0.35
                - waterNeighbourCount(world, candidate) * 0.04;
    }

    /** 落点周围一圈（九格）里有多少格也是可抛水面：开阔的水面更容易抛也更容易收。 */
    private static int waterNeighbourCount(World world, BlockPos pos) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (world.castableWater(pos.offset(dx, 0, dz))) count++;
            }
        }
        return count;
    }
}
