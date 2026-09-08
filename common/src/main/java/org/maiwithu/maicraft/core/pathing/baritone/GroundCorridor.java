package org.maiwithu.maicraft.core.pathing.baritone;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;

/** Continuous same-height walking: swept body clearance and gap-free support intervals. */
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

    public boolean clear(Vec3 from, Vec3 to) {
        if (exhausted() || from == null || to == null || !Double.isFinite(from.lengthSqr() + to.lengthSqr())
                || !Double.isFinite(width + height) || width <= 0 || width > 2 || height <= 0 || height > 4
                || Math.abs(from.y - to.y) > EPS || from.distanceToSqr(to) > MAX_LENGTH * MAX_LENGTH
                || !physical.clearSegment(from, to, width, height)) return false;
        var view = new LoadedView(world, loaded, this::charge);
        try { return inspect(view, from, to); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    private boolean inspect(BlockGetter view, Vec3 from, Vec3 to) {
        double half = width / 2;
        var supports = new ArrayList<double[]>();
        for (int x = Mth.floor(Math.min(from.x, to.x) - half) - 1; x <= Math.floor(Math.max(from.x, to.x) + half) + 1; x++) {
            for (int z = Mth.floor(Math.min(from.z, to.z) - half) - 1; z <= Math.floor(Math.max(from.z, to.z) + half) + 1; z++) {
                // Shape owners one cell outside the swept body can protrude into its path.
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
                        // Keep a real portion of the footprint supported, including cell seams.
                        double contact = Math.max(0, half - Math.min(0.05, half / 2));
                        double[] interval = interval(from, to, box.minX - contact, box.maxX + contact,
                                box.minZ - contact, box.maxZ + contact);
                        if (interval != null) supports.add(interval);
                    }
                }
            }
        }
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

    /** Guard neighbor reads made by native collision implementations too; cache for this query only. */
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
