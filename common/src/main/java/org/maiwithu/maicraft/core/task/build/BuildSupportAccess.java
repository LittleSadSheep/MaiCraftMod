// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

/** 逐块证明支撑自身能放下，再证明最终目标；每一步只使用此前支撑前缀和真实地面，不借未来平台。 */
final class BuildSupportAccess {
    private static final int MAX_VISITED = 512;
    private static final long SLICE_NANOS = 4_000_000;
    private final LocalPlayer player;
    private final BuildTaskRecord.Target target;
    private final List<BlockPos> supports;
    private final BlockState material;
    private final Predicate<BlockPos> allowed;
    private final Vec3 origin;
    private final BuildSupportWorld world;
    private final PhysicalObstacleSnapshot physical;
    private final LongSet forbidden;
    private final Map<BlockPos, BlockState> prefix = new LinkedHashMap<>();
    private final Map<BlockPos, BuildPlacementAccessSearch.Access> supportPlacements = new LinkedHashMap<>();
    private final List<Map<String, Object>> faces = new ArrayList<>();
    private BuildPlacementAccessSearch search;
    private Vec3 cursor;
    private int stepIndex, reachableStances;
    private BuildPlacementGeometry.Gesture witness;
    private boolean initialized, complete;
    private int checkedStances;
    private String reason = "checking_projected_support_access";
    private String invalidation;

    BuildSupportAccess(LocalPlayer player, BuildTaskRecord.Target target, List<BlockPos> supports,
                       BlockState material, Predicate<BlockPos> allowed, LongSet forbidden,
                       PhysicalObstacleSnapshot physical) {
        this.player = player; this.target = target; this.material = material; this.allowed = allowed; this.physical = physical;
        this.supports = supports.stream().map(BlockPos::immutable).toList(); origin = player.position();
        this.forbidden = LongSets.unmodifiable(new LongOpenHashSet(forbidden));
        Map<BlockPos, BlockState> added = new LinkedHashMap<>();
        this.supports.forEach(pos -> added.put(pos, material));
        world = new BuildSupportWorld(player.level(), player.level()::isLoaded, added);
    }

    boolean advance(int budget) {
        long deadline = System.nanoTime() + SLICE_NANOS;
        try {
            for (int work = 0; work < Math.max(0, budget) && !complete && System.nanoTime() < deadline; work++) {
                if (!initialized) { initialize(); continue; }
                if (!search.advance(1)) continue;
                checkedStances += search.checked(); reachableStances += search.visited();
                if (!search.accepted()) {
                    reject(stepIndex < supports.size() ? "support_step_not_placeable:" + search.reason() : "no_verified_post_support_stance:" + search.reason());
                    break;
                }
                if (stepIndex < supports.size()) {
                    BlockPos support = supports.get(stepIndex++); supportPlacements.put(support, search.access());
                    cursor = search.access().feet(); prefix.put(support, material);
                    // 前一步有实际可达放置见证后才让下一步看到它；全链仍共享原来的512个落脚点上限。
                    if (reachableStances >= MAX_VISITED) { reject("support_access_search_budget"); break; }
                    startStep(); continue;
                }
                witness = search.access().gesture(); invalidation = currentIssue();
                if (invalidation != null) reject("support_access_observation_changed");
                else { complete = true; reason = "projected_stance_and_click_face_verified"; }
            }
        } catch (RuntimeException | LinkageError unavailable) { reject("support_access_observation_unavailable"); }
        return complete;
    }

    private void initialize() {
        initialized = true;
        if (supports.isEmpty() || supports.size() > 32 || new HashSet<>(supports).size() != supports.size()
                || material.canBeReplaced() || material.hasBlockEntity() || !material.getFluidState().isEmpty()
                || !material.isCollisionShapeFullBlock(world, target.pos())) { reject("invalid_support_projection"); return; }
        for (BlockPos pos : supports) if (pos.equals(target.pos()) || !player.level().isLoaded(pos)
                || pos.getY() < world.getMinBuildHeight() || pos.getY() >= world.getMinBuildHeight() + world.getHeight()
                || !player.level().getBlockState(pos).isAir() || !allowed.test(pos)) {
            reject("support_projection_site_changed"); return;
        }
        int closed = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.pos().relative(direction);
            BlockState state = world.getBlockState(neighbor);
            boolean full = state.isCollisionShapeFullBlock(world, neighbor);
            if (full) closed++;
            faces.add(Map.of("face", direction.getName(), "full_collision", full,
                    "block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    "proposed_support", supports.contains(neighbor)));
        }
        if (world.sawUnloaded()) { reject("support_access_unloaded"); return; }
        if (closed == 6) { reject("target_enclosed_after_supports"); return; }
        cursor = origin; startStep();
    }

    private void startStep() {
        var goal = stepIndex < supports.size() ? new BuildTaskRecord.Target(material, material.getBlock().asItem(),
                supports.get(stepIndex), "temporary support prerequisite", null, null, null).asItemPlace() : target;
        search = new BuildPlacementAccessSearch(player, goal, world.withProjection(prefix), cursor, forbidden, physical,
                MAX_VISITED - reachableStances, false);
    }

    boolean accepted() { return complete && witness != null; }
    BuildPlacementGeometry.Gesture witness() { return witness; }
    BuildPlacementAccessSearch.Access placementFor(BlockPos support) { return supportPlacements.get(support); }
    BuildPlacementAccessSearch.Access targetPlacement() { return accepted() ? search.access() : null; }
    boolean current() { return currentIssue() == null; }
    // 已按见证走到新站位后只复查环境；有意走动不是旧起点失效，但任何地形或保护变化仍须重新证明。
    boolean environmentCurrent() { return environmentIssue() == null; }
    String invalidationReason() { String current = currentIssue(); return current == null ? invalidation : current; }
    private String currentIssue() {
        String environment = environmentIssue();
        return environment != null ? environment : origin.distanceToSqr(player.position()) > .01 ? "support_access_body_moved" : null;
    }
    private String environmentIssue() {
        // 先检查世界和权限，再区分只是身体起点变了；真实环境变化不能伪装成惯性位移而获得重试许可。
        if (!physical.boxes().equals(EmbeddedBaritoneRuntime.physicalObstacles().boxes())) return "support_access_physical_changed";
        for (BlockPos pos : supports) if (!allowed.test(pos) || !player.level().isLoaded(pos)
                || !player.level().getBlockState(pos).isAir()) return "support_projection_site_changed";
        if (!world.unchanged()) return "support_access_world_changed";
        return null;
    }
    private void reject(String value) { complete = true; witness = null; reason = value; }
    Map<String, Object> evidence() {
        var data = new LinkedHashMap<String, Object>(Map.of("reason", reason, "verified", accepted(), "proposed_supports", supports.size(),
                "checked_stances", checkedStances + (!complete && search != null ? search.checked() : 0),
                "reachable_stances", reachableStances + (!complete && search != null ? search.visited() : 0),
                "observed_blocks", world.reads(), "target_faces", List.copyOf(faces)));
        data.put("verified_support_steps", supportPlacements.size()); data.put("active_support_step", stepIndex);
        if (invalidation != null) data.put("invalidation", invalidation);
        return Map.copyOf(data);
    }
}
