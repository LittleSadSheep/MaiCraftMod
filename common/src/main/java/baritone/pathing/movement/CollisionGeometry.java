package baritone.pathing.movement;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Extra collision outside a block's own cell, absent from Baritone's per-cell costs. */
public final class CollisionGeometry {
    private static final double EPS = 1.0E-5;
    private final BlockGetter view;
    private final boolean frozen;
    private final Vec3 initialPosition;
    private final BlockPos initialCell;
    private final Long2ObjectOpenHashMap<List<AABB>> protrusions = new Long2ObjectOpenHashMap<>();

    public CollisionGeometry(BlockGetter view, boolean frozen, Vec3 initialPosition, BlockPos initialCell) {
        this.view = view;
        this.frozen = frozen;
        this.initialPosition = initialPosition;
        this.initialCell = initialCell;
    }

    public boolean clear(int x, int y, int z, int toX, int toY, int toZ) {
        Vec3 from = initialCell != null && initialCell.getX() == x && initialCell.getY() == y && initialCell.getZ() == z
                ? initialPosition : new Vec3(x + 0.5, feetHeight(x, y, z), z + 0.5);
        Vec3 to = new Vec3(toX + 0.5, feetHeight(toX, toY, toZ), toZ + 0.5);
        boolean horizontal = x != toX || z != toZ;
        double top = Math.max(from.y, to.y);
        if (horizontal && (toY > y || Math.max(Math.abs(toX - x), Math.abs(toZ - z)) > 1)) {
            top = Math.max(top, feetHeight(x, y, z) + 1.25);
        }
        Vec3 raisedFrom = new Vec3(from.x, top, from.z), raisedTo = new Vec3(to.x, top, to.z);
        return (from.y == top || clearSegment(from, raisedFrom))
                && clearSegment(raisedFrom, raisedTo) && (to.y == top || clearSegment(raisedTo, to));
    }

    private double feetHeight(int x, int y, int z) {
        double height = supportHeight(view, new BlockPos(x, y - 1, z));
        return Double.isNaN(height) ? y : y - 1 + height;
    }

    /** Highest actual support under the player's central 0.6-wide footprint. */
    public static double supportHeight(BlockGetter view, BlockPos pos) {
        double highest = Double.NaN;
        var shape = view.getBlockState(pos).getCollisionShape(view, pos, CollisionContext.empty());
        for (AABB box : shape.toAabbs()) {
            if (box.maxX > 0.2 && box.minX < 0.8 && box.maxZ > 0.2 && box.minZ < 0.8) {
                highest = Double.isNaN(highest) ? box.maxY : Math.max(highest, box.maxY);
            }
        }
        return highest;
    }

    private boolean clearSegment(Vec3 from, Vec3 to) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // The same one-cell owner margin used by vanilla block collision lookup; no entities.
        for (int x = Mth.floor(Math.min(from.x, to.x) - 0.3) - 1; x <= Mth.floor(Math.max(from.x, to.x) + 0.3) + 1; x++) {
            for (int z = Mth.floor(Math.min(from.z, to.z) - 0.3) - 1; z <= Mth.floor(Math.max(from.z, to.z) + 0.3) + 1; z++) {
                for (int y = Mth.floor(Math.min(from.y, to.y)) - 1; y <= Mth.floor(Math.max(from.y, to.y) + 1.8) + 1; y++) {
                    pos.set(x, y, z);
                    BlockState state = view.getBlockState(pos);
                    if (!state.hasLargeCollisionShape() || state.getBlock() instanceof FenceGateBlock
                            || state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()) continue;
                    List<AABB> boxes = frozen ? protrusions.get(pos.asLong()) : null;
                    if (boxes == null) {
                        var shape = state.getCollisionShape(view, pos, CollisionContext.empty());
                        boxes = Shapes.joinUnoptimized(shape, Shapes.block(), BooleanOp.ONLY_FIRST).toAabbs();
                        if (frozen) {
                            if (protrusions.size() >= 4096) protrusions.clear();
                            protrusions.put(pos.asLong(), boxes);
                        }
                    }
                    for (AABB box : boxes) {
                        // Minkowski expansion tests the swept player body, not just endpoint cells
                        // or the larger axis-aligned rectangle enclosing a diagonal movement.
                        AABB expanded = new AABB(x + box.minX - 0.3 + EPS, y + box.minY - 1.8 + EPS,
                                z + box.minZ - 0.3 + EPS, x + box.maxX + 0.3 - EPS,
                                y + box.maxY - EPS, z + box.maxZ + 0.3 - EPS);
                        if (expanded.contains(from) || expanded.contains(to) || expanded.clip(from, to).isPresent()) return false;
                    }
                }
            }
        }
        return true;
    }
}
