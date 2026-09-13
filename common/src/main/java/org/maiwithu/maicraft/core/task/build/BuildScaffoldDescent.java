// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import static org.maiwithu.maicraft.core.task.build.BuildScaffoldDescentGeometry.*;

/** 拆自有柱的只读证明与回执门：原生挖掘由外层驱动，一格确认且身体落稳后才允许下一格。 */
final class BuildScaffoldDescent {
    enum Status { READY, DESCENDING, LANDED, EXIT_REQUIRED, COMPLETE, REJECTED }
    record Step(BlockPos block, BlockState expected, Vec3 from, Vec3 landing) {}
    private final LocalPlayer player;
    private final Object level;
    private final Map<BlockPos, BlockState> owned;
    private final Predicate<BlockPos> permitted;
    private final LongSet forbidden;
    private final Function<Vec3, PhysicalObstacleSnapshot> physical;
    private final View view;
    private final double width, height;
    private final List<Step> steps = new ArrayList<>();
    private final Set<BlockPos> gone = new HashSet<>();
    private final BuildSupportSettling settling = new BuildSupportSettling();
    private List<Vec3> exitRoute = List.of();
    private Status status = Status.REJECTED;
    private String reason = "descent_not_proved";
    private int index, observedTicks;
    private long lastTick = Long.MIN_VALUE;
    private boolean armed, acknowledged;

    static BuildScaffoldDescent inspect(LocalPlayer player, BlockPos first, Map<BlockPos, BlockState> owned,
                                        Predicate<BlockPos> permitted, LongSet forbidden) {
        return inspect(player, first, owned, permitted, forbidden, focus -> PhysicalObstacleSnapshot.capture(player.clientLevel, focus));
    }
    static BuildScaffoldDescent inspect(LocalPlayer player, BlockPos first, Map<BlockPos, BlockState> owned,
            Predicate<BlockPos> permitted, LongSet forbidden, Function<Vec3, PhysicalObstacleSnapshot> physical) {
        var result = new BuildScaffoldDescent(player, owned, permitted, forbidden, physical);
        try { result.plan(first); } catch (RuntimeException | LinkageError unavailable) { result.reject("descent_observation_unavailable"); }
        return result;
    }
    private BuildScaffoldDescent(LocalPlayer player, Map<BlockPos, BlockState> owned, Predicate<BlockPos> permitted,
                                  LongSet forbidden, Function<Vec3, PhysicalObstacleSnapshot> physical) {
        this.player = player; level = player.level(); this.owned = Map.copyOf(owned); this.permitted = permitted;
        this.forbidden = new LongOpenHashSet(forbidden); this.physical = physical;
        width = player.getBbWidth(); height = Math.max(player.getBbHeight(), player.getDimensions(Pose.STANDING).height());
        view = new View(player.level(), pos -> player.level().isLoaded(pos) && player.level().getWorldBorder().isWithinBounds(pos));
    }
    private void plan(BlockPos first) {
        Vec3 from = player.position();
        if (!quiet() || !first.equals(player.blockPosition().below()) || Math.abs(from.y - first.getY() - 1) > 1e-5
                || Math.abs(from.x - first.getX() - .5) + width / 2 > .45
                || Math.abs(from.z - first.getZ() - .5) + width / 2 > .45) { reject("descent_needs_settled_full_footprint"); return; }
        if (!owned.containsKey(first)) { reject("descent_first_block_unowned"); return; }
        var removed = new HashSet<BlockPos>(); BlockPos cell = first;
        // 先看整条最多三十二格的真实自有柱与最终退路，再允许拆第一格；底部没有地面就保留整柱。
        while (owned.containsKey(cell)) {
            if (steps.size() >= 32) { reject("descent_column_limit"); return; }
            BlockState state = view.getBlockState(cell);
            if (!state.equals(owned.get(cell)) || !canRemove(cell) || state.getBlock().getClass() != Block.class
                    || !state.isCollisionShapeFullBlock(view, cell) || state.getDestroySpeed(player.level(), cell) < 0
                    || !state.getFluidState().isEmpty() || state.hasBlockEntity()) { reject("descent_column_changed_or_not_plain_solid"); return; }
            Vec3 landing = from.add(0, -1, 0); removed.add(cell);
            if (!frame(view.without(removed), from).drop(from, landing)) { reject("descent_step_not_supported"); return; }
            steps.add(new Step(cell.immutable(), state, from, landing)); from = landing; cell = cell.below();
        }
        exitRoute = frame(view.without(removed), from).exit(from, owned.keySet());
        if (exitRoute.isEmpty()) { reject("descent_no_connected_exit_ground"); return; }
        status = Status.READY; reason = "descent_column_and_exit_verified";
    }
    boolean beforeBreak() {
        if (status != Status.READY) return false;
        try {
            Step step = step();
            if (!quiet() || player.position().distanceToSqr(step.from) > .04 * .04
                    || !current(gone, index)) return reject("descent_break_boundary_changed");
            var removed = new HashSet<>(gone); removed.add(step.block);
            Vec3 landing = new Vec3(player.getX(), step.landing.y, player.getZ());
            if (!frame(view.without(gone), player.position()).ground().clear(player.position(), player.position())
                    || !frame(view.without(removed), player.position()).drop(player.position(), landing)
                    || !exitCurrent()) return reject("descent_break_geometry_changed");
            armed = true; return true;
        } catch (RuntimeException | LinkageError unavailable) { return reject("descent_break_observation_unavailable"); }
    }
    // removalAcknowledged 只接收本格 BlockDigger 的原生 CONFIRMED_APPLIED；它与实际空气、身体落稳分别核对。
    Status observe(boolean removalAcknowledged, long tick) {
        if (status == Status.REJECTED || status == Status.COMPLETE || status == Status.LANDED) return status;
        try {
            if (player.level() != level || !ordinary()) { reject("descent_body_changed"); return status; }
            if (status == Status.EXIT_REQUIRED) { observeExit(tick); return status; }
            if (!armed) { reject("descent_break_not_armed"); return status; }
            Step step = step(); acknowledged |= removalAcknowledged;
            if (!view.loaded.test(step.block)) { reject("descent_step_unloaded"); return status; }
            boolean air = player.level().getBlockState(step.block).isAir();
            if (!air) { if (acknowledged || !current(gone, index)) reject("descent_confirmation_without_removal"); return status; }
            // 客户端先看到空气时也只观察下落；原生回执晚到不能提前开挖第二格，更不能把空气本身算作本次确认。
            status = Status.DESCENDING; var removed = new HashSet<>(gone); removed.add(step.block);
            Vec3 at = player.position(), landing = new Vec3(at.x, step.landing.y, at.z);
            if (!current(removed, index + 1) || at.y > step.from.y + 1e-5 || at.y < step.landing.y - .02
                    || at.subtract(step.from).horizontalDistance() > .05 || player.getDeltaMovement().y > .02
                    || !frame(view.without(removed), at).drop(at, landing)) { reject("descent_left_verified_drop"); return status; }
            count(tick);
            var stable = settling.observe(at, player.getDeltaMovement(), player.onGround() && Math.abs(at.y - landing.y) <= .02, ordinary(), tick);
            if (stable == BuildSupportSettling.Status.READY && acknowledged) { status = Status.LANDED; reason = "descent_step_confirmed_and_settled"; }
            else if (observedTicks >= 60) reject("descent_confirmation_or_landing_timeout");
        } catch (RuntimeException | LinkageError unavailable) { reject("descent_observation_unavailable"); }
        return status;
    }
    boolean advance() {
        if (status != Status.LANDED) return false;
        gone.add(step().block); index++; armed = acknowledged = false; observedTicks = 0; lastTick = Long.MIN_VALUE; settling.reset();
        status = index == steps.size() ? Status.EXIT_REQUIRED : Status.READY; return true;
    }
    private void observeExit(long tick) {
        // 柱已拆完也不算离场完成；角色须正常走到已证明的连片地面，并再次真实站稳。
        if (!current(gone, index) || !exitCurrent()) { reject("descent_exit_changed"); return; }
        count(tick); Vec3 at = player.position();
        // 走向出口的时间和站稳观察分别计时，不能在尚未到达时耗尽六十刻站稳器、导致之后永远无法完成。
        if (at.distanceToSqr(exit()) > .01) { settling.reset(); if (observedTicks >= 200) reject("descent_exit_timeout"); return; }
        var stable = settling.observe(at, player.getDeltaMovement(), player.onGround(), ordinary(), tick);
        if (stable == BuildSupportSettling.Status.READY) { status = Status.COMPLETE; reason = "descent_exit_reached"; }
        else if (observedTicks >= 200) reject("descent_exit_timeout");
    }
    private boolean exitCurrent() {
        var removed = new HashSet<BlockPos>(); for (var step : steps) removed.add(step.block);
        return frame(view.without(removed), steps.getLast().landing).exit(steps.getLast().landing, owned.keySet()).equals(exitRoute);
    }
    private boolean current(Set<BlockPos> removed, int remaining) {
        if (player.level() != level || !view.current(removed)) return false;
        for (int at = remaining; at < steps.size(); at++) if (!canRemove(steps.get(at).block)) return false;
        return true;
    }
    private boolean canRemove(BlockPos pos) { return permitted.test(pos) && !NavigationSafetyContext.protectsMutation(pos); }
    private Frame frame(View projected, Vec3 focus) {
        var snapshot = physical.apply(focus);
        if (!Set.of("not_installed", "ready", "ready_empty").contains(snapshot.state())) throw new IllegalStateException("descent_physics_unknown");
        var boxes = new ArrayList<>(snapshot.boxes()); AABB interest = body(focus, width, height).inflate(4);
        for (var shape : player.level().getEntityCollisions(player, interest)) {
            boxes.addAll(shape.toAabbs()); if (boxes.size() > 4096) throw new IllegalStateException("descent_entity_budget");
        }
        var hard = new LongOpenHashSet(forbidden); hard.addAll(NavigationSafetyContext.forbiddenBodyCells());
        return new Frame(projected, width, height, hard, new PhysicalObstacleSnapshot(boxes, snapshot.blockReads(), snapshot.conservativeStructures(), snapshot.state()));
    }
    private boolean ordinary() { return !player.isInWater() && !player.isPassenger() && finite(player.position()) && finite(player.getDeltaMovement())
            && Math.abs(player.getBbWidth() - width) <= 1e-5 && player.getBbHeight() <= height + 1e-5
            && (player.getPose() == Pose.STANDING || player.getPose() == Pose.CROUCHING); }
    private boolean quiet() { return ordinary() && player.onGround() && player.getDeltaMovement().horizontalDistance() <= .01 && player.getDeltaMovement().y <= .02; }
    private void count(long tick) { if (tick != lastTick) { observedTicks++; lastTick = tick; } }
    private boolean reject(String detail) { status = Status.REJECTED; reason = detail; return false; }
    void cancel() { reject("descent_cancelled"); }
    boolean accepted() { return status != Status.REJECTED; }
    Status status() { return status; }
    String reason() { return reason; }
    Step step() { return index < steps.size() ? steps.get(index) : null; }
    List<Step> steps() { return List.copyOf(steps); }
    List<Vec3> exitRoute() { return exitRoute; }
    Vec3 exit() { return exitRoute.isEmpty() ? null : exitRoute.getLast(); }
    Map<String, Object> evidence() { return Map.of("state", status.name(), "reason", reason, "column_blocks", steps.size(),
            "confirmed_steps", index, "native_acknowledged", acknowledged, "observed_ticks", observedTicks, "observed_blocks", view.observed.size()); }
}
