// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.BiPredicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;

/**
 * 寻找能从一个站位连续放多格的位置，减少每放一块就重新走路。这里只选站位和点击方案，不执行动作。
 * 候选必须能沿当前到目标的地面直线通道到达；需要绕障碍的路线留给后续其他导航处理。
 */
final class BuildWorksitePlanner {
    private static final long SLICE_NANOS = 4_000_000;

    record Placement(BuildTaskRecord.Target target, BuildPlacementGeometry.Gesture gesture) {}
    record Worksite(BlockPos stance, Vec3 feet, List<Placement> placements, double distanceSquared) {
        Worksite { stance = stance.immutable(); placements = List.copyOf(placements); }
        int coverage() { return placements.size(); }
    }
    /** A partial result may be used immediately, but an unfinished empty result is never no-path. */
    record Progress(boolean complete, Worksite best, int candidateChecks, int placementChecks) {}

    private BuildWorksitePlanner() {}

    static final class Search {
        private final LocalPlayer player;
        private final List<BuildTaskRecord.Target> seeds;
        private final Map<Long, BuildTaskRecord.Target> targets;
        private final Predicate<BlockPos> allowed;
        private final LongSet forbidden;
        private final Set<BlockPos> rejected;
        private final BiPredicate<BuildTaskRecord.Target, BuildPlacementGeometry.Gesture> gestureAllowed;
        private final CandidateRows candidates = new CandidateRows();
        private final Map<BlockPos, List<BuildTaskRecord.Target>> buckets = new HashMap<>();
        private final Vec3 origin;
        private int preparedAt, candidateChecks, placementChecks, nearbyAt;
        private boolean candidatesDone;
        private Worksite best;
        private BlockPos scoringCell;
        private Vec3 scoringFeet;
        private List<BuildTaskRecord.Target> nearby = List.of();
        private final List<Placement> placements = new ArrayList<>();

        /** Restart after confirmed placement/world change; proofs are observations, never action receipts. */
        Search(LocalPlayer player, List<BuildTaskRecord.Target> pending,
               Map<Long, BuildTaskRecord.Target> targets, Predicate<BlockPos> allowed,
               LongSet forbidden, Set<BlockPos> rejected,
               BiPredicate<BuildTaskRecord.Target, BuildPlacementGeometry.Gesture> gestureAllowed) {
            this.player = player; this.targets = targets; this.allowed = allowed;
            this.gestureAllowed = gestureAllowed;
            this.forbidden = forbidden; this.rejected = Set.copyOf(rejected); origin = player.position();
            seeds = pending.stream().filter(t -> !BuildCellRules.isAirTarget(t)
                            && BuildPlacementGeometry.primaryOf(t).equals(t.pos()))
                    .sorted(Comparator.comparingDouble((BuildTaskRecord.Target t) -> t.pos().distToCenterSqr(origin))
                            .thenComparing(BuildOrder.BUILD_ORDER)).toList();
        }

        // 每次按工作项数量和约四毫秒推进，保存候选与评分进度；返回已有最佳方案不代表所有候选都查完。
        Progress advance(int workBudget) {
            long deadline = System.nanoTime() + SLICE_NANOS;
            for (int work = 0; work < Math.max(0, workBudget) && System.nanoTime() < deadline; work++) {
                if (scoringCell != null) {
                    if (nearbyAt < nearby.size()) {
                        var target = nearby.get(nearbyAt++); placementChecks++;
                        if (player.level().isLoaded(target.pos()) && !target.matches(player.level().getBlockState(target.pos()))) {
                            var gesture = BuildPlacementGeometry.liveGestureFrom(player, target, targets, scoringFeet,
                                    candidate -> gestureAllowed.test(target, candidate));
                            if (gesture != null) placements.add(new Placement(target, gesture));
                        }
                    } else finishCandidate();
                    continue;
                }
                // 先只给目前有可点击支撑的目标生成站位范围，并按每四格分区，便于后续查附近目标。
                if (preparedAt < seeds.size()) {
                    var target = seeds.get(preparedAt++);
                    if (supported(target)) {
                        candidates.add(target.pos());
                        buckets.computeIfAbsent(bucket(target.pos()), ignored -> new ArrayList<>()).add(target);
                    }
                    continue;
                }
                BlockPos cell = candidates.next();
                if (cell == null) { candidatesDone = true; break; }
                if (rejected.contains(cell) || !allowed.test(cell)) continue;
                candidateChecks++;
                // No plan overlay: the floor and the whole walking corridor must already exist.
                var corridor = new GroundCorridor(player.level(), player.level()::isLoaded,
                        player.getBbWidth(), player.getBbHeight(), forbidden, EmbeddedBaritoneRuntime.physicalObstacles());
                Vec3 feet = corridor.stance(cell);
                if (feet == null || !corridor.clear(origin, feet)) continue;
                scoringCell = cell; scoringFeet = feet; nearbyAt = 0; placements.clear();
                nearby = nearby(feet);
            }
            return new Progress(candidatesDone && scoringCell == null, best, candidateChecks, placementChecks);
        }

        private boolean supported(BuildTaskRecord.Target target) {
            if (!player.level().isLoaded(target.pos())) return false;
            var stage = new BuildPlacementStage(player.level(), player.level()::isLoaded, targets, target, false);
            if (!stage.state(target.pos()).isAir()) return true;
            for (var direction : net.minecraft.core.Direction.values())
                if (stage.support(target.pos().relative(direction), direction.getOpposite())) return true;
            return false;
        }

        // 先查周围分区，再保留距估计眼睛位置六格以内的目标，最后按施工顺序检查。这里用固定 1.62 格眼高。
        private List<BuildTaskRecord.Target> nearby(Vec3 feet) {
            List<BuildTaskRecord.Target> result = new ArrayList<>();
            BlockPos base = bucket(BlockPos.containing(feet));
            // Five-block reach plus the clicked neighboring shape; buckets avoid rescanning the entire blueprint.
            for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++)
                for (var target : buckets.getOrDefault(base.offset(x, y, z), List.of()))
                    if (target.pos().distToCenterSqr(feet.add(0, 1.62, 0)) <= 36) result.add(target);
            result.sort(BuildLayerFrontier.order(targets));
            return result;
        }

        private void finishCandidate() {
            if (!placements.isEmpty()) {
                var worksite = new Worksite(scoringCell, scoringFeet, placements, origin.distanceToSqr(scoringFeet));
                if (best == null || compare(worksite, best) < 0) best = worksite;
            }
            scoringCell = null; scoringFeet = null; nearby = List.of(); placements.clear();
        }

        private static BlockPos bucket(BlockPos pos) {
            return new BlockPos(Math.floorDiv(pos.getX(), 4), Math.floorDiv(pos.getY(), 4), Math.floorDiv(pos.getZ(), 4));
        }
    }

    /** Merge overlapping candidate squares as row intervals before enumerating any stance. */
    private static final class CandidateRows {
        private final Map<BlockPos, TreeMap<Integer, Integer>> rows = new LinkedHashMap<>();
        private Iterator<Map.Entry<BlockPos, TreeMap<Integer, Integer>>> rowIterator;
        private Iterator<Map.Entry<Integer, Integer>> ranges = List.<Map.Entry<Integer, Integer>>of().iterator();
        private BlockPos row;
        private int x, end = -1;

        // 每个目标周围生成横向各四格、向下两格和向上一格的候选；同一 y/z 行的重叠 x 区间合并。
        void add(BlockPos target) {
            for (int dy = -2; dy <= 1; dy++) for (int dz = -4; dz <= 4; dz++) {
                var ranges = rows.computeIfAbsent(new BlockPos(0, target.getY() + dy, target.getZ() + dz),
                        ignored -> new TreeMap<>());
                int start = target.getX() - 4, finish = target.getX() + 4;
                var lower = ranges.floorEntry(start);
                if (lower != null && lower.getValue() + 1 >= start) {
                    start = lower.getKey(); finish = Math.max(finish, lower.getValue()); ranges.remove(lower.getKey());
                }
                for (var next = ranges.ceilingEntry(start); next != null && next.getKey() <= finish + 1;
                        next = ranges.ceilingEntry(start)) {
                    finish = Math.max(finish, next.getValue()); ranges.remove(next.getKey());
                }
                ranges.put(start, finish);
            }
        }

        // 按已合并的区间逐格取候选，不把整片范围提前展开成大量 BlockPos。
        BlockPos next() {
            if (rowIterator == null) rowIterator = rows.entrySet().iterator();
            while (x > end) {
                if (ranges.hasNext()) {
                    var range = ranges.next(); x = range.getKey(); end = range.getValue();
                } else {
                    if (!rowIterator.hasNext()) return null;
                    var next = rowIterator.next(); row = next.getKey(); ranges = next.getValue().entrySet().iterator();
                }
            }
            return new BlockPos(x++, row.getY(), row.getZ());
        }
    }

    // 先选可施工格数多的位置，再选走动距离短的位置；仍相同时按施工顺序和坐标稳定排序。
    private static int compare(Worksite a, Worksite b) {
        int count = Integer.compare(b.coverage(), a.coverage());
        if (count != 0) return count;
        int distance = Double.compare(a.distanceSquared(), b.distanceSquared());
        if (distance != 0) return distance;
        int order = BuildOrder.BUILD_ORDER.compare(a.placements().getFirst().target(), b.placements().getFirst().target());
        return order != 0 ? order : a.stance().compareTo(b.stance());
    }
}
