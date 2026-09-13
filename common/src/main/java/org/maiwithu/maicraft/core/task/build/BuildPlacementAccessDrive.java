// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.entity.InputDriver;

/** 已证明的实地路线 -> 安全锚点 -> 原生潜行贴边 -> 等点击回执后退回；不向空中格心寻路，也不改地形。 */
final class BuildPlacementAccessDrive {
    enum Status { RUNNING, READY, UNAVAILABLE, FAILED }
    private final LocalPlayer player;
    private final BuildTaskRecord.Target target;
    private final PlayerNav.ContextProvider context;
    private final BooleanSupplier allowed;
    private final Supplier<Status> prepareItem;
    private final Predicate<BuildPlacementGeometry.Gesture> gestureAllowed;
    private final BuildSupportSettling settling = new BuildSupportSettling();
    private BuildSupportWorld observation;
    private BuildPlacementAccessSearch search;
    private BuildPlacementAccessSearch.Access access;
    private BuildPlacementGeometry.Gesture gesture;
    private PlayerNav nav;
    private BuildEdgeMotion edge, returning, anchorAlignment;
    private boolean approached, prepared, itemPrepared, required, anchorAligned, paused, resumeFromEdge;
    private int routes;
    private long deadline;
    private String phase = "settling", failure;
    private Status status = Status.RUNNING;

    BuildPlacementAccessDrive(LocalPlayer player, BuildTaskRecord.Target target, PlayerNav.ContextProvider context,
                              BooleanSupplier allowed, BuildPlacementAccessSearch.Access known, Supplier<Status> prepareItem) {
        this(player, target, context, allowed, known, prepareItem, ignored -> true);
    }
    BuildPlacementAccessDrive(LocalPlayer player, BuildTaskRecord.Target target, PlayerNav.ContextProvider context,
                              BooleanSupplier allowed, BuildPlacementAccessSearch.Access known, Supplier<Status> prepareItem,
                              Predicate<BuildPlacementGeometry.Gesture> gestureAllowed) {
        this.player = player; this.target = target; this.context = context; this.allowed = allowed; this.prepareItem = prepareItem;
        this.gestureAllowed = gestureAllowed;
        access = known; required = known != null; deadline = player.level().getGameTime() + 600;
    }
    Status tick() {
        resumeClock();
        if (status != Status.RUNNING) { if (status == Status.READY) hold(); return status; }
        if (player.level().getGameTime() >= deadline) return fail("placement_access_timeout");
        if (!allowed.getAsBoolean()) return fail("placement_access_protection_changed");
        if (access == null) {
            InputDriver.halt(player);
            if (search == null) {
                var settled = settle();
                if (settled == BuildSupportSettling.Status.FAILED) return fail("placement_access_body_unsettled");
                if (settled != BuildSupportSettling.Status.READY) return Status.RUNNING;
                observation = new BuildSupportWorld(player.level(), player.level()::isLoaded, Map.of());
                search = new BuildPlacementAccessSearch(player, target, observation, player.position(), forbidden(),
                        EmbeddedBaritoneRuntime.physicalObstacles(), 512, true); phase = "finding_real_edge";
            }
            if (!search.advance(16)) return Status.RUNNING;
            if (!search.accepted() || !observation.unchanged()) {
                failure = search.accepted() ? "placement_access_observation_changed" : search.reason();
                status = Status.UNAVAILABLE; return status;
            }
            access = search.access(); settling.reset();
        }
        if (!approached) {
            // 只向已证明有实地的锚点发一次普通导航；最后不足一格的偏移由精确潜行原语完成。
            if (nav == null && player.onGround() && Math.abs(player.getY() - access.approach().y) < .06
                    && player.position().distanceToSqr(access.approach()) <= .8 * .8) {
                approached = true; settling.reset();
            } else {
                if (nav == null) {
                    if (++routes > 1) return fail("placement_access_route_exhausted");
                    BlockPos anchor = BlockPos.containing(access.approach());
                    nav = PlayerNav.toGoal(player, () -> NavGoal.exact(anchor), BuildStanceNavigation.PRECISE_WALK,
                            () -> player.onGround() && Math.abs(player.getY() - access.approach().y) < .06
                                    && player.position().distanceToSqr(access.approach()) <= .64, context).walkingOnly();
                    phase = "walking_to_real_anchor";
                }
                var navigation = nav.tick();
                if (navigation == PlayerNav.Status.FAILED) return fail("placement_access_route_failed:" + nav.failReason());
                if (navigation == PlayerNav.Status.ARRIVED) { nav.stop(); nav = null; approached = true; settling.reset(); }
                return Status.RUNNING;
            }
        }
        if (!prepared) {
            InputDriver.halt(player); phase = "settling_at_anchor";
            var settled = settle();
            if (settled == BuildSupportSettling.Status.FAILED) return fail("placement_access_body_unsettled");
            if (settled != BuildSupportSettling.Status.READY) return Status.RUNNING;
            prepared = true;
        }
        if (!itemPrepared) {
            // 导航进入锚点附近还不够：先用同一原生微动对齐到实地锚点，站稳后才能打开背包换材料。
            if (!anchorAligned) {
                if (anchorAlignment == null) anchorAlignment = BuildEdgeMotion.alignAt(access.approach(), forbidden(), this::bodyAllowed);
                phase = "aligning_safe_anchor";
                var aligned = anchorAlignment.tick(player);
                if (aligned == BuildEdgeMotion.Status.FAILED) return fail(anchorAlignment.failure());
                if (aligned != BuildEdgeMotion.Status.ARRIVED) return Status.RUNNING;
                anchorAlignment.release(player); anchorAlignment = null; anchorAligned = true; resumeFromEdge = false;
                return Status.RUNNING;
            }
            // 在有完整地板的锚点先拿好材料并等必要背包界面关闭，再贴边；避免在檐边为换物品反复松潜行。
            phase = "preparing_material_at_anchor"; var material = prepareItem.get();
            if (material == Status.FAILED || material == Status.UNAVAILABLE) {
                failure = "placement_access_material_unavailable"; stop(); phase = "failed"; status = Status.FAILED; return status;
            }
            if (material != Status.READY) return Status.RUNNING;
            itemPrepared = true; return Status.RUNNING;
        }
        if (access.edge()) {
            if (edge == null) edge = new BuildEdgeMotion(access.approach(), access.feet(), forbidden(), this::bodyAllowed);
            phase = "crouching_to_edge";
            var moved = edge.tick(player);
            if (moved == BuildEdgeMotion.Status.FAILED) return fail(edge.failure());
            if (moved != BuildEdgeMotion.Status.ARRIVED) return Status.RUNNING;
        }
        // 到位后再按真实位置和原生潜行眼高找点击，计划中的面不能直接冒充此刻准星能点击的面。
        var live = new BuildSupportWorld(player.level(), player.level()::isLoaded, Map.of());
        gesture = BuildPlacementGeometry.projectedGestureFrom(player, target, live, player.level()::isLoaded, player.position(), access.edge(), gestureAllowed);
        if (gesture == null || !allowed.getAsBoolean()) return fail("placement_access_live_click_unavailable");
        phase = "ready"; status = Status.READY; hold(); return status;
    }
    Status returnToAnchor() {
        resumeClock();
        if (edge == null && !resumeFromEdge) return Status.READY;
        // 点击确认后先退回原实地锚点，再交还下一段普通导航，避免把外侧部分踩空的导航格当新起点。
        if (returning == null) returning = new BuildEdgeMotion(access.feet(), access.approach(), forbidden(), this::bodyAllowed);
        phase = "returning_to_real_anchor";
        var moved = returning.tick(player);
        if (moved == BuildEdgeMotion.Status.FAILED) return fail(returning.failure());
        if (moved != BuildEdgeMotion.Status.ARRIVED) return Status.RUNNING;
        returning.release(player); if (edge != null) edge.release(player);
        returning = null; edge = null; resumeFromEdge = false; phase = "returned"; return Status.READY;
    }
    boolean edgeActive() { return edge != null || resumeFromEdge; }
    void rejectedGesture() {
        // 保留已证实的站位，但重新读取世界并筛选新的放法；READY 不能把刚被拒绝的旧手法再次交回。
        if (status == Status.READY) { status = Status.RUNNING; gesture = null; }
    }
    BuildPlacementGeometry.Gesture gesture() { return gesture; }
    String failure() { return failure; }
    long deadline() { return deadline; }
    Status hold() {
        var active = returning != null ? returning : anchorAlignment != null ? anchorAlignment : edge;
        if (active != null && active.hold(player) == BuildEdgeMotion.Status.FAILED) {
            failure = active.failure(); phase = "failed"; status = Status.FAILED;
        }
        return status;
    }
    void stop() { if (nav != null) { nav.stop(); nav = null; } if (returning != null) returning.stop(player); if (anchorAlignment != null) anchorAlignment.stop(player); if (edge != null) edge.stop(player); }
    void pause() {
        // 暂停不在后台抢输入；恢复时重新取得控制租约、落稳并对齐锚点，不能复用已过期的运动控制器。
        resumeFromEdge |= edge != null || returning != null;
        if (nav != null) { nav.pause(); nav = null; }
        edge = returning = anchorAlignment = null; prepared = itemPrepared = anchorAligned = false;
        approached = false; settling.reset(); paused = true;
    }
    private void resumeClock() {
        if (paused) { paused = false; routes = 0; deadline = player.level().getGameTime() + 600; status = Status.RUNNING; }
    }
    private Status fail(String reason) { failure = reason; stop(); phase = "failed"; status = required || edge != null ? Status.FAILED : Status.UNAVAILABLE; return status; }
    private LongSet forbidden() { return context.embeddedForbiddenBodyCells(); }
    private boolean bodyAllowed(BlockPos pos) { return !context.embeddedForbiddenBodyCells().contains(pos.asLong()); }
    private BuildSupportSettling.Status settle() { return settling.observe(player.position(), player.getDeltaMovement(), player.onGround(),
            !player.isInWater() && !player.isPassenger(), player.level().getGameTime()); }
    Map<String, Object> evidence() {
        var data = new LinkedHashMap<String, Object>(); data.put("phase", phase); data.put("route_attempts", routes);
        if (failure != null) data.put("reason", failure);
        if (search != null) { data.put("reachable_stances", search.visited()); data.put("checked_stances", search.checked()); }
        // 锚点未对齐时也公开实际身体与目标的差异，不能只留下笼统的“贴边失败”而丢失半阶高度证据。
        if (anchorAlignment != null) data.put("anchor_alignment", anchorAlignment.evidence());
        if (returning != null) data.put("edge", returning.evidence()); else if (edge != null) data.put("edge", edge.evidence());
        return Map.copyOf(data);
    }
}
