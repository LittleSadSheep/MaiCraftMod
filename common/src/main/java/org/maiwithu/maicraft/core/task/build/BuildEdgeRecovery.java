// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** 放置回执结算后自动换退路：保持潜行 -> 查现有落脚点 -> 原生慢走脱离 -> 验证可以站立再交还施工。 */
public final class BuildEdgeRecovery {
    public enum Status { RUNNING, READY, FAILED }
    private final LocalPlayer player;
    private final Object level;
    private final LongSet forbidden;
    private final Predicate<BlockPos> permitted;
    private BuildSupportWorld world;
    private BuildEdgeRecoverySearch search;
    private BuildEdgeMotion motion;
    private List<Vec3> route = List.of();
    private int next, replans, searches;
    private long deadline;
    private boolean searching, stopped, paused;
    private String reason = "starting_alternate_exit";
    private Status status = Status.RUNNING;

    public BuildEdgeRecovery(LocalPlayer player, LongSet forbidden, Predicate<BlockPos> permitted) {
        this.player = player; level = player.level(); this.forbidden = forbidden; this.permitted = permitted;
        deadline = player.level().getGameTime() + 600;
    }
    public Status tick() {
        if (status != Status.RUNNING || stopped) return status;
        if (paused) { paused = false; deadline = player.level().getGameTime() + 600; }
        if (player.level() != level || player.level().getGameTime() >= deadline) return fail("alternate_exit_scope_or_deadline_changed");
        if (motion == null && search == null && !restart(false)) return status;
        if (searching) {
            // 搜索期间仍续真实潜行；身体或控制权已改变时不借重新搜索抢回输入。
            if (motion.tick(player) == BuildEdgeMotion.Status.FAILED) return fail(motion.failure());
            if (!search.advance()) return Status.RUNNING;
            if (!world.unchanged()) { restart(true); return status; }
            if (!search.found()) return fail(search.reason());
            route = search.route(); next = 1; searching = false; motion.release(player); motion = null;
        }
        if (next >= route.size()) {
            if (!BuildEdgeMotion.canStandAt(player, forbidden, permitted)) { restart(true); return status; }
            if (motion != null) motion.release(player);
            reason = "alternate_supported_exit_reached"; return status = Status.READY;
        }
        if (motion == null) motion = new BuildEdgeMotion(player.position(), route.get(next), forbidden, permitted);
        var moved = motion.tick(player);
        if (moved == BuildEdgeMotion.Status.FAILED) {
            if (recoverable(motion.failure())) restart(true); else fail(motion.failure());
        } else if (moved == BuildEdgeMotion.Status.ARRIVED) {
            next++;
            if (next >= route.size() && BuildEdgeMotion.canStandAt(player, forbidden, permitted)) {
                motion.release(player); reason = "alternate_supported_exit_reached"; return status = Status.READY;
            }
            // 中间拐点仍可能踩着窄边，交接这一刻继续原地潜行；下一刻再接下一小段，不能留下松 Shift 的空窗。
            motion.release(player); motion = new BuildEdgeMotion(player.position(), player.position(), forbidden, permitted);
            if (motion.tick(player) == BuildEdgeMotion.Status.FAILED) return fail(motion.failure());
            motion = null;
        }
        return status;
    }
    private boolean restart(boolean changed) {
        if (changed && ++replans > 3) { fail("alternate_exit_changed_repeatedly"); return false; }
        searches++;
        if (motion != null) motion.stop(player);
        world = new BuildSupportWorld(player.level(), player.level()::isLoaded, Map.of());
        search = new BuildEdgeRecoverySearch(player, world, forbidden, permitted,
                PhysicalObstacleSnapshot.capture(player.clientLevel, player.position()));
        route = List.of(); next = 0; searching = true;
        motion = new BuildEdgeMotion(player.position(), player.position(), forbidden, permitted);
        reason = "replanning_supported_exit";
        if (motion.tick(player) == BuildEdgeMotion.Status.FAILED) { fail(motion.failure()); return false; }
        return true;
    }
    /** 只重试地形／站位变化；换世界、失去控制、未落地或许可变化必须交还对应流程。 */
    static boolean recoverable(String failure) {
        return List.of("edge_support_or_sweep_changed", "edge_hold_support_changed", "edge_stance_moved_after_arrival",
                "placement_return_obstructed_before_click", "placement_access_live_click_unavailable").contains(failure);
    }
    public void pause() {
        if (motion != null) motion.stop(player);
        motion = null; search = null; route = List.of(); searching = false;
        paused = true;
    }
    public void stop() { if (motion != null) motion.stop(player); stopped = true; }
    private Status fail(String detail) { reason = detail; return status = Status.FAILED; }
    public Map<String, Object> evidence() {
        return Map.of("state", status.name().toLowerCase(Locale.ROOT), "reason", reason,
                "searches", searches, "terrain_replans", replans, "visited_stances", search == null ? 0 : search.visited(),
                "route_steps", route.size(), "completed_steps", next, "world_mutations", 0);
    }
}
