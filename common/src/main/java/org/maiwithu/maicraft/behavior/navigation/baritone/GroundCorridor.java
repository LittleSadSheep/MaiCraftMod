// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.maiwithu.maicraft.behavior.navigation.transport.TransportLanding;

/**
 * 检查角色能否沿一条同高度直线走过去：整个身体不能擦进障碍，脚下沿途都要有连续支撑，还要避开危险和保护格。
 * 一次对象共用方块读取预算；资料不足、形状读取失败或预算用尽时不给这条直线放行。
 */
public final class GroundCorridor {
    private static final double EPS = 1e-5;
    public static final double MAX_LENGTH = 128;
    private final BlockGetter world;
    private final Predicate<BlockPos> loaded;
    private final double width, height;
    private final LongSet forbidden;
    private final PhysicalObstacleSnapshot physical;
    private int remainingReads = 16384;

    public GroundCorridor(BlockGetter world, Predicate<BlockPos> loaded, double width, double height,
                          LongSet forbidden, PhysicalObstacleSnapshot physical) {
        this.world = world; this.loaded = loaded; this.width = width; this.height = height;
        this.forbidden = forbidden; this.physical = physical;
    }

    public Vec3 stance(BlockPos cell) {
        if (exhausted()) return null;
        var destination = TransportLanding.inspect(new LoadedView(world, loaded, this::charge), loaded,
                cell, width, height, forbidden).destination();
        return destination == null ? null : destination.landingPoint();
    }

    public boolean exhausted() { return remainingReads <= 0; }
    private void charge() { if (remainingReads-- <= 0) throw new IllegalStateException("corridor observation budget"); }

    /** 缩短跳跃前，身体经过的每个柱列上方都必须存在同一高度的完整顶棚。 */
    public boolean hasContinuousCeiling(Vec3 from, Vec3 to, double clearance) {
        if (exhausted() || from == null || to == null || !Double.isFinite(from.lengthSqr() + to.lengthSqr())
                || !Double.isFinite(width + clearance) || width <= 0 || width > 2 || clearance <= 0
                || Math.abs(from.y - to.y) > EPS || from.distanceToSqr(to) > MAX_LENGTH * MAX_LENGTH) return false;
        int y = Mth.floor(from.y + clearance);
        if (Math.abs(from.y + clearance - y) > EPS) return false;
        var view = new LoadedView(world, loaded, this::charge);
        double half = width / 2;
        try {
            for (int x = Mth.floor(Math.min(from.x, to.x) - half); x <= Mth.floor(Math.max(from.x, to.x) + half); x++) {
                for (int z = Mth.floor(Math.min(from.z, to.z) - half); z <= Mth.floor(Math.max(from.z, to.z) + half); z++) {
                    if (interval(from, to, x - half + EPS, x + 1 + half - EPS,
                            z - half + EPS, z + 1 + half - EPS) == null) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = view.getBlockState(pos);
                    if (!state.getFluidState().isEmpty() || !state.isCollisionShapeFullBlock(view, pos)) return false;
                }
            }
            return true;
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    public boolean clear(Vec3 from, Vec3 to) {
        if (exhausted() || from == null || to == null || !Double.isFinite(from.lengthSqr() + to.lengthSqr())
                || !Double.isFinite(width + height) || width <= 0 || width > 2 || height <= 0 || height > 4
                || Math.abs(from.y - to.y) > EPS || from.distanceToSqr(to) > MAX_LENGTH * MAX_LENGTH
                || !physical.clearSegment(from, to, width, height)) return false;
        var view = new LoadedView(world, loaded, this::charge);
        try { return inspect(view, from, to); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    /**
     * 下坑走行段：碰撞、禁入格与危险格的检查与 {@link #clear} 完全相同，但支撑覆盖允许在坑口断开——
     * 走向低一格凹格的水平段末端本来就悬在坑口上方。断口处身体跨过的每个无支撑柱列必须经 gap 放行，
     * 调用方用“该柱列本身也是恰低一格的可站立开口”兜底，保证每一步要么踩着支撑、要么是普通的一格下踏。
     */
    public boolean descentWalk(Vec3 from, Vec3 to, Predicate<BlockPos> gapAllowed) {
        if (exhausted() || from == null || to == null || !Double.isFinite(from.lengthSqr() + to.lengthSqr())
                || !Double.isFinite(width + height) || width <= 0 || width > 2 || height <= 0 || height > 4
                || Math.abs(from.y - to.y) > EPS || from.distanceToSqr(to) > MAX_LENGTH * MAX_LENGTH
                || !physical.clearSegment(from, to, width, height)) return false;
        var view = new LoadedView(world, loaded, this::charge);
        try { return inspectDescent(view, from, to, gapAllowed); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    private boolean inspectDescent(BlockGetter view, Vec3 from, Vec3 to, Predicate<BlockPos> gapAllowed) {
        double half = width / 2;
        var supports = new ArrayList<double[]>();
        var gapCells = new ArrayList<BlockPos>();
        var gapSpans = new ArrayList<double[]>();
        for (int x = Mth.floor(Math.min(from.x, to.x) - half) - 1; x <= Math.floor(Math.max(from.x, to.x) + half) + 1; x++) {
            for (int z = Mth.floor(Math.min(from.z, to.z) - half) - 1; z <= Math.floor(Math.max(from.z, to.z) + half) + 1; z++) {
                // 位于身体扫掠范围外一格的形状所有者也可能向路线凸出。
                if (interval(from, to, x - half - 1, x + half + 2, z - half - 1, z + half + 2) == null) continue;
                var columnSpans = new ArrayList<double[]>();
                for (int y = Mth.floor(from.y) - 2; y <= Math.floor(from.y + height) + 1; y++) {
                    BlockPos cell = new BlockPos(x, y, z);
                    BlockState state = view.getBlockState(cell);
                    AABB owner = new AABB(cell);
                    if (hits(from, to, owner, half, height, false) && forbidden.contains(cell.asLong())
                            || hits(from, to, owner, half, height, true) && TransportLanding.unsafe(view, cell, state)) return false;
                    for (AABB local : state.getCollisionShape(view, cell, CollisionContext.empty()).toAabbs()) {
                        AABB box = local.move(cell);
                        if (hits(from, to, box, half, height, false)) return false;
                        if (Math.abs(box.maxY - from.y) > EPS) continue;
                        // 保证脚底轮廓仍有真实支撑，包括方块格交界处。
                        double contact = Math.max(0, half - Math.min(0.05, half / 2));
                        double[] span = interval(from, to, box.minX - contact, box.maxX + contact,
                                box.minZ - contact, box.maxZ + contact);
                        if (span != null) { supports.add(span); columnSpans.add(span); }
                    }
                }
                // 身体扫过该柱列而站立层的可踩顶面盖不住扫掠段时记为断口柱列（含整列无顶面的开口）；
                // 脚位取站立层那格，供调用方核它能否落脚。
                double[] over = interval(from, to, x - half, x + 1 + half, z - half, z + 1 + half);
                if (over != null && !covers(columnSpans, over)) {
                    gapCells.add(new BlockPos(x, Mth.floor(from.y), z));
                    gapSpans.add(over);
                }
            }
        }
        // 支撑未覆盖的路段即断口；断口柱列的扫掠区间与之重叠时要求调用方逐柱放行。
        supports.sort(Comparator.comparingDouble(span -> span[0]));
        double covered = 0;
        var gaps = new ArrayList<double[]>();
        for (double[] span : supports) {
            if (span[0] > covered + EPS) gaps.add(new double[]{covered, span[0]});
            covered = Math.max(covered, span[1]);
        }
        if (covered < 1 - EPS) gaps.add(new double[]{covered, 1});
        for (int i = 0; i < gapCells.size(); i++) {
            double[] span = gapSpans.get(i);
            boolean overGap = gaps.stream().anyMatch(gap -> span[0] <= gap[1] - EPS && span[1] >= gap[0] + EPS);
            if (overGap && !gapAllowed.test(gapCells.get(i))) return false;
        }
        return true;
    }

    /** 各段排序后能否连续盖住整个区间。 */
    private static boolean covers(List<double[]> spans, double[] range) {
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        double covered = range[0];
        for (double[] span : spans) {
            if (span[0] > covered + EPS) return false;
            covered = Math.max(covered, span[1]);
            if (covered >= range[1] - EPS) return true;
        }
        return covered >= range[1] - EPS;
    }

    private boolean inspect(BlockGetter view, Vec3 from, Vec3 to) {
        double half = width / 2;
        var supports = new ArrayList<double[]>();
        for (int x = Mth.floor(Math.min(from.x, to.x) - half) - 1; x <= Math.floor(Math.max(from.x, to.x) + half) + 1; x++) {
            for (int z = Mth.floor(Math.min(from.z, to.z) - half) - 1; z <= Math.floor(Math.max(from.z, to.z) + half) + 1; z++) {
                // 位于身体扫掠范围外一格的形状所有者也可能向路线凸出。
                if (interval(from, to, x - half - 1, x + half + 2, z - half - 1, z + half + 2) == null) continue;
                for (int y = Mth.floor(from.y) - 2; y <= Math.floor(from.y + height) + 1; y++) {
                    BlockPos cell = new BlockPos(x, y, z);
                    BlockState state = view.getBlockState(cell);
                    AABB owner = new AABB(cell);
                    if (hits(from, to, owner, half, height, false) && forbidden.contains(cell.asLong())
                            || hits(from, to, owner, half, height, true) && TransportLanding.unsafe(view, cell, state)) return false;
                    for (AABB local : state.getCollisionShape(view, cell, CollisionContext.empty()).toAabbs()) {
                        AABB box = local.move(cell);
                        if (hits(from, to, box, half, height, false)) return false;
                        if (Math.abs(box.maxY - from.y) > EPS || TransportLanding.unsafe(view, cell, state)) continue;
                        // 保证脚底轮廓仍有真实支撑，包括方块格交界处。
                        double contact = Math.max(0, half - Math.min(0.05, half / 2));
                        double[] interval = interval(from, to, box.minX - contact, box.maxX + contact,
                                box.minZ - contact, box.maxZ + contact);
                        if (interval != null) supports.add(interval);
                    }
                }
            }
        }
        // 把各块地面能支撑的路段排好序，再从起点连到终点；中间有任何断开的区间就不能直接走。
        supports.sort(Comparator.comparingDouble(interval -> interval[0]));
        double covered = 0;
        for (double[] interval : supports) {
            if (interval[0] > covered + EPS) return false;
            covered = Math.max(covered, interval[1]);
            if (covered >= 1 - EPS) return true;
        }
        return false;
    }

    private static boolean hits(Vec3 from, Vec3 to, AABB box, double half, double height, boolean contact) {
        if (box.maxY <= from.y + (contact ? -EPS : EPS) || box.minY >= from.y + height - EPS) return false;
        return interval(from, to, box.minX - half + EPS, box.maxX + half - EPS,
                box.minZ - half + EPS, box.maxZ + half - EPS) != null;
    }

    // 把一块障碍或支撑面投影到整条路线，求它覆盖从起点到终点的哪一段，避免只检查几个离散采样点。
    private static double[] interval(Vec3 from, Vec3 to, double minX, double maxX, double minZ, double maxZ) {
        double[] result = {0, 1};
        return clip(from.x, to.x - from.x, minX, maxX, result)
                && clip(from.z, to.z - from.z, minZ, maxZ, result) ? result : null;
    }

    private static boolean clip(double origin, double delta, double min, double max, double[] interval) {
        if (Math.abs(delta) < EPS) return origin >= min && origin <= max;
        double a = (min - origin) / delta, b = (max - origin) / delta;
        interval[0] = Math.max(interval[0], Math.min(a, b));
        interval[1] = Math.min(interval[1], Math.max(a, b));
        return interval[0] <= interval[1];
    }

    /** 同时防护原生碰撞实现对邻格的读取；缓存仅在本次查询期间有效。 */
    private record LoadedView(BlockGetter delegate, Predicate<BlockPos> loaded, Runnable charge, Map<BlockPos, BlockState> states)
            implements BlockGetter {
        LoadedView(BlockGetter delegate, Predicate<BlockPos> loaded, Runnable charge) { this(delegate, loaded, charge, new HashMap<>()); }
        private void check(BlockPos pos) { if (!loaded.test(pos)) throw new IllegalStateException("unloaded corridor"); }
        public BlockState getBlockState(BlockPos pos) {
            check(pos); return states.computeIfAbsent(pos.immutable(), cell -> { charge.run(); return delegate.getBlockState(cell); });
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { check(pos); charge.run(); return delegate.getBlockEntity(pos); }
        public int getHeight() { return delegate.getHeight(); }
        public int getMinBuildHeight() { return delegate.getMinBuildHeight(); }
    }
}
