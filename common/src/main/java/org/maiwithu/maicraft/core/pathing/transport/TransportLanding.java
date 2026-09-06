package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;

/** Read-only static landing geometry, including shape owners one cell outside the body's volume. */
public final class TransportLanding {
    private static final double EPS = 1.0E-5;
    private TransportLanding() {}
    public record Probe(TransportTargets.Destination destination, boolean unloaded, boolean unknown) {}
    private record Cell(BlockPos pos, BlockState state, List<AABB> boxes) {}

    public static Probe inspect(BlockGetter view, Predicate<BlockPos> loaded, BlockPos feet,
                                double width, double height, LongSet forbidden) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || width > 2 || height <= 0 || height > 4) {
            return new Probe(null, false, true);
        }
        var guarded = new LoadedView(view, loaded);
        try {
            return new Probe(find(guarded, feet, width, height, forbidden), false, false);
        } catch (Unloaded ignored) {
            return new Probe(null, true, false);
        } catch (RuntimeException unknownShape) {
            return new Probe(null, false, true);
        }
    }

    private static TransportTargets.Destination find(BlockGetter view, BlockPos feet,
                                                      double width, double height, LongSet forbidden) {
        double x = feet.getX() + 0.5, z = feet.getZ() + 0.5, half = width / 2;
        List<Cell> cells = new ArrayList<>();
        List<Double> tops = new ArrayList<>();
        for (int bx = (int) Math.floor(x - half) - 1; bx <= Math.floor(x + half) + 1; bx++) {
            for (int bz = (int) Math.floor(z - half) - 1; bz <= Math.floor(z + half) + 1; bz++) {
                for (int by = feet.getY() - 2; by <= feet.getY() + Math.ceil(height) + 1; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    BlockState state = view.getBlockState(pos);
                    List<AABB> boxes = state.getCollisionShape(view, pos, CollisionContext.empty()).toAabbs()
                            .stream().map(box -> box.move(pos)).toList();
                    cells.add(new Cell(pos, state, boxes));
                    // Node feet represent the cell above support. An occupied target's own
                    // partial machine shape must not silently become a different landing floor.
                    if (by >= feet.getY() || unsafe(view, pos, state)) continue;
                    for (AABB box : boxes) {
                        if (box.maxX > x - half + EPS && box.minX < x + half - EPS
                                && box.maxZ > z - half + EPS && box.minZ < z + half - EPS
                                && box.maxY >= feet.getY() - 1 + EPS && box.maxY < feet.getY() + 1) tops.add(box.maxY);
                    }
                }
            }
        }
        tops.sort(Comparator.reverseOrder());
        for (double top : tops.stream().distinct().toList()) {
            if (top < view.getMinBuildHeight() || top + height > view.getMaxBuildHeight()
                    || !BlockHelper.playerFeet(view, x, top, z).equals(feet)) continue;
            AABB body = new AABB(x - half + EPS, top + EPS, z - half + EPS,
                    x + half - EPS, top + height - EPS, z + half - EPS);
            AABB contact = new AABB(body.minX, top - EPS, body.minZ, body.maxX, body.maxY, body.maxZ);
            boolean blocked = false;
            for (Cell cell : cells) {
                AABB owner = new AABB(cell.pos());
                if ((body.intersects(owner) && forbidden.contains(cell.pos().asLong()))
                        || (contact.intersects(owner) && unsafe(view, cell.pos(), cell.state()))
                        || cell.boxes().stream().anyMatch(body::intersects)) { blocked = true; break; }
            }
            if (!blocked) return new TransportTargets.Destination(feet, new Vec3(x, top, z));
        }
        return null;
    }

    private static boolean unsafe(BlockGetter view, BlockPos pos, BlockState state) {
        return !state.getFluidState().isEmpty() || BlockHelper.isHazard(view, pos)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_GATEWAY)
                || state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.BIG_DRIPLEAF)
                || (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) && state.getValue(BlockStateProperties.LIT)
                || state.getBlock() instanceof MovingPistonBlock || state.getBlock() instanceof ScaffoldingBlock
                || state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Collision implementations also receive the loaded guard for their neighbor reads. */
    private record LoadedView(BlockGetter delegate, Predicate<BlockPos> loaded) implements BlockGetter {
        private void check(BlockPos pos) {
            if (!loaded.test(pos)) throw new Unloaded();
        }
        public BlockState getBlockState(BlockPos pos) { check(pos); return delegate.getBlockState(pos); }
        public FluidState getFluidState(BlockPos pos) { check(pos); return delegate.getFluidState(pos); }
        public BlockEntity getBlockEntity(BlockPos pos) { check(pos); return delegate.getBlockEntity(pos); }
        public int getHeight() { return delegate.getHeight(); }
        public int getMinBuildHeight() { return delegate.getMinBuildHeight(); }
    }
    private static final class Unloaded extends RuntimeException {
        private Unloaded() { super(null, null, false, false); }
    }
}
