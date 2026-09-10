// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
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

/** Prove a useful final stance in a support-only projection before granting any support placement. */
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
    private final BuildSupportWalking walking;
    private final PhysicalObstacleSnapshot physical;
    private final ArrayDeque<Vec3> frontier = new ArrayDeque<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private final List<Map<String, Object>> faces = new ArrayList<>();
    private BuildPlacementGeometry.Gesture witness;
    private boolean initialized, complete;
    private int checkedStances;
    private String reason = "checking_projected_support_access";

    BuildSupportAccess(LocalPlayer player, BuildTaskRecord.Target target, List<BlockPos> supports,
                       BlockState material, Predicate<BlockPos> allowed, LongSet forbidden,
                       PhysicalObstacleSnapshot physical) {
        this.player = player; this.target = target; this.material = material; this.allowed = allowed; this.physical = physical;
        this.supports = supports.stream().map(BlockPos::immutable).toList(); origin = player.position();
        Map<BlockPos, BlockState> added = new LinkedHashMap<>();
        this.supports.forEach(pos -> added.put(pos, material));
        world = new BuildSupportWorld(player.level(), player.level()::isLoaded, added);
        walking = new BuildSupportWalking(world, player.level()::isLoaded, player.getBbWidth(),
                Math.max(player.getBbHeight(), player.getDimensions(net.minecraft.world.entity.Pose.STANDING).height()), forbidden, physical);
    }

    boolean advance(int budget) {
        long deadline = System.nanoTime() + SLICE_NANOS;
        try {
            for (int work = 0; work < Math.max(0, budget) && !complete && System.nanoTime() < deadline; work++) {
                if (!initialized) { initialize(); continue; }
                Vec3 feet = frontier.pollFirst();
                if (feet == null) { reject(world.sawUnloaded() ? "support_access_unloaded" : "no_verified_post_support_stance"); break; }
                if (feet.add(0, player.getEyeHeight(), 0).distanceToSqr(Vec3.atCenterOf(target.pos())) <= 36) {
                    checkedStances++;
                    witness = BuildPlacementGeometry.projectedGestureFrom(player, target, world, player.level()::isLoaded, feet);
                    if (witness != null) {
                        if (!current()) reject("support_access_observation_changed");
                        else { complete = true; reason = "projected_stance_and_click_face_verified"; }
                        break;
                    }
                }
                for (Direction direction : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
                    BlockPos next = BlockPos.containing(feet).relative(direction).offset(0, dy, 0);
                    if (!within(next) || visited.contains(next)) continue;
                    Vec3 landing = walking.stance(next);
                    if (landing == null || !walking.edge(feet, landing)) continue;
                    BlockPos key = BlockPos.containing(landing);
                    if (!within(key) || visited.contains(key)) continue;
                    if (visited.size() >= MAX_VISITED) { reject("support_access_search_budget"); return true; }
                    visited.add(key); frontier.addLast(landing);
                }
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
                    "block_id", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    "proposed_support", supports.contains(neighbor)));
        }
        if (world.sawUnloaded()) { reject("support_access_unloaded"); return; }
        if (closed == 6) { reject("target_enclosed_after_supports"); return; }
        Vec3 start = walking.stance(BlockPos.containing(origin));
        if (start == null || !within(BlockPos.containing(start)) || !walking.edge(origin, start)) {
            reject("current_footing_not_connected_after_supports"); return;
        }
        visited.add(BlockPos.containing(start)); frontier.add(start);
    }

    private boolean within(BlockPos pos) {
        return Math.abs(pos.getX() - target.pos().getX()) <= 8 && Math.abs(pos.getZ() - target.pos().getZ()) <= 8
                && pos.getY() >= Math.min(origin.y, target.pos().getY()) - 2
                && pos.getY() <= Math.max(origin.y, target.pos().getY()) + 2;
    }

    boolean accepted() { return complete && witness != null; }
    BuildPlacementGeometry.Gesture witness() { return witness; }
    boolean current() {
        if (origin.distanceToSqr(player.position()) > .01) return false;
        if (!physical.boxes().equals(org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime.physicalObstacles().boxes())) return false;
        for (BlockPos pos : supports) if (!allowed.test(pos) || !player.level().isLoaded(pos)
                || !player.level().getBlockState(pos).isAir()) return false;
        return world.unchanged();
    }
    private void reject(String value) { complete = true; witness = null; reason = value; }
    Map<String, Object> evidence() {
        return Map.of("reason", reason, "verified", accepted(), "proposed_supports", supports.size(),
                "checked_stances", checkedStances, "reachable_stances", visited.size(),
                "observed_blocks", world.reads(), "target_faces", List.copyOf(faces));
    }
}
