package org.maiwithu.maicraft.core.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Stateless terrain predicates used by the movement primitives and the A*
 * search: decide
 * whether a block can be stood on, passed through, broken, or is a hazard —
 * without any entity instance, just a {@link BlockGetter} + {@link BlockPos}.
 *
 * <h2>Coordinate convention</h2>
 * A "feet position" {@code p} is walkable as a standing spot when:
 * <ul>
 *   <li>{@code p} and {@code p.above()} are pass-through (air/non-colliding) —
 *       room for the 2-tall entity body, and</li>
 *   <li>{@code p.below()} is solid-walkable — something to stand on.</li>
 * </ul>
 */
public final class BlockHelper {

    private BlockHelper() {}

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /**
     * Can the entity's body occupy this cell (no collision, not a fluid we
     * refuse to enter)? True for air, grass, flowers, etc.
     *
     * <p>Water is handled here, in the passability predicate itself, rather than
     * as a bolt-on swim move. Flowing
     * water is refused (the current shoves us off-path); still water is passable
     * ONLY as the SURFACE cell — i.e. nothing fluid directly above it — so the
     * move graph contains the water surface plane but never a submerged corridor.
     * Lava is never passable. This is what lets the generic traverse/ascend/
     * descend route across water with no dedicated swim edge.
     */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER)) return false;       // lava etc. — never
            if (isFlowingWater(level, pos)) return false;        // current shoves us
            // Surface only: a fluid (or a lily pad) directly above means we'd be
            // submerged / capped here, not at a free surface.
            BlockState up = level.getBlockState(pos.above());
            if (!up.getFluidState().isEmpty()) return false;
            return !up.is(Blocks.LILY_PAD);
        }
        // Explicit deny-list: blocks we must never path through.
        // Many have an EMPTY/partial collision shape (fire, cobweb, sweet-berry, powder
        // snow, open trapdoor, big dripleaf…) so the raw shape test below would wrongly
        // pass them; the explicit list is what stops the body walking into them.
        if (block instanceof BaseFireBlock
                || state.is(Blocks.COBWEB) || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COCOA) || block instanceof AbstractSkullBlock
                || state.is(Blocks.BUBBLE_COLUMN) || block instanceof ShulkerBoxBlock
                || block instanceof SlabBlock || block instanceof TrapDoorBlock
                || state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.END_ROD)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POINTED_DRIPSTONE)
                || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock
                || state.is(Blocks.BIG_DRIPLEAF) || state.is(Blocks.POWDER_SNOW)
                || block instanceof CauldronBlock) {
            return false;
        }
        // Wooden doors / fence gates are passable even when shut — the path
        // executor opens them by hand. Iron doors stay a hard
        // obstruction: no redstone, can't open. So they fall through to the
        // collision test below and read as solid.
        if (isOpenableDoor(state)) {
            return true;
        }
        // A thin carpet floor cover is always walkable over.
        if (block instanceof CarpetBlock) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        return shape.isEmpty();
    }

    /**
     * A wooden door or fence gate the body can open by hand — NOT an iron door
     * (needs redstone). The path treats these as passable (no breaking) and the
     * executor right-clicks them open when shut.
     */
    public static boolean isOpenableDoor(BlockState state) {
        if (state.is(Blocks.IRON_DOOR)) {
            return false;
        }
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    /** Is this door/gate currently open (OPEN blockstate property true)? */
    public static boolean isDoorOpen(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
    }

    /**
     * Can a body approaching the
     * door/gate at {@code doorPos} from the adjacent cell {@code fromPos} pass through it
     * AS IT CURRENTLY STANDS? A fence gate is passable iff open. A door is
     * orientation-aware: passable iff {@code (facingAxis == approachAxis) == open}
     * — so an open door perpendicular to the approach still BLOCKS (its panel swung across the
     * gap), and a closed door flush with the approach does not. The executor toggles (right-clicks)
     * any door/gate this reports as NOT passable, which both opens a blocking-closed one and
     * closes a blocking-open one. Non-door/gate blocks read as passable (not our concern).
     */
    public static boolean isDoorwayPassable(BlockGetter level, BlockPos doorPos, BlockPos fromPos) {
        BlockState state = level.getBlockState(doorPos);
        Block block = state.getBlock();
        if (block instanceof FenceGateBlock) {
            return state.getValue(BlockStateProperties.OPEN);
        }
        if (!(block instanceof DoorBlock)) {
            return true;
        }
        if (fromPos.equals(doorPos)) {
            return false;
        }
        Direction.Axis facing = state.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = state.getValue(BlockStateProperties.OPEN);
        Direction.Axis approach;
        if (fromPos.north().equals(doorPos) || fromPos.south().equals(doorPos)) {
            approach = Direction.Axis.Z;
        } else if (fromPos.east().equals(doorPos) || fromPos.west().equals(doorPos)) {
            approach = Direction.Axis.X;
        } else {
            return true;   // not cardinally adjacent (diagonal / wrong Y) → don't toggle
        }
        return (facing == approach) == open;
    }

    /** Is this cell water (source or flowing)? */
    public static boolean isWater(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState()
                .is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * Is this a FLOWING water cell?
     * A non-source level is flowing; a source block is "flowing" too when it
     * feeds a horizontal non-source neighbour (edge of a pool) — unsafe to
     * walk because the current there pushes you. Used to keep the
     * route on still water only.
     */
    public static boolean isFlowingWater(BlockGetter level, BlockPos pos) {
        FluidState fluid = level.getBlockState(pos).getFluidState();
        if (!fluid.is(FluidTags.WATER)) return false;
        if (!fluid.isSource()) return true;                 // amount < 8 → flowing
        for (Direction d : HORIZONTAL) {
            FluidState n = level.getBlockState(pos.relative(d)).getFluidState();
            if (n.is(FluidTags.WATER) && !n.isSource()) return true;
        }
        return false;
    }

    /**
     * Can the entity stand on TOP of this block (i.e. is it a solid floor)?
     *
     * <p>Water: a water
     * cell is "walkable on" iff there is water directly ABOVE it — i.e. you
     * float at the surface, treading on the submerged column, never on the very
     * top (air-headed) cell. Combined with {@link #canWalkThrough}'s surface
     * rule, this pins the body to the water surface plane.
     */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER)) return false;   // lava is never a floor
            // Walk on water only where water is above (submerged → float here).
            return isWater(level, pos.above());
        }
        if (state.isAir()) return false;
        // Explicit allow-list: blocks that are NOT full collision
        // cubes but are still safe to stand on. The generic full-cube test below would
        // miss these (farmland/path are 15/16 tall, chests/ladders/azalea aren't cubes).
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.SOUL_SAND)) return true;
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.ENDER_CHEST)) return true;
        if (state.is(Blocks.GLASS) || state.getBlock() instanceof StainedGlassBlock) return true;
        if (state.is(Blocks.LADDER)) return true;
        if (state.getBlock() instanceof AzaleaBlock) return true;
        if (state.getBlock() instanceof StairBlock) return true;
        if (state.getBlock() instanceof SlabBlock) {
            // ALL slabs are a floor. Standing
            // on a bottom slab is reconciled by playerFeet() returning the cell ABOVE it.
            return true;
        }
        // Magma / honey are full cubes but refused as floors (damage / stickiness).
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.HONEY_BLOCK)) return false;
        // Everything else: a normal full collision cube.
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * The body's feet cell for pathing — the
     * position nudged up 0.1251 (so sinking on soul sand / farmland doesn't read a block
     * low) and, when that cell is a SLAB, taken as the cell ABOVE it. The slab adjustment
     * is what reconciles standing on a bottom slab (feet at slab.y+0.5) with the move graph,
     * where a move onto a slab targets the cell ABOVE the slab.
     */
    public static BlockPos playerFeet(BlockGetter level, double x, double y, double z) {
        BlockPos f = BlockPos.containing(x, y + 0.1251, z);
        if (level.getBlockState(f).getBlock() instanceof SlabBlock) {
            return f.above();
        }
        return f;
    }

    /** A slab occupying the bottom half of its cell. */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    public static boolean isBottomSlab(BlockGetter level, BlockPos pos) {
        return isBottomSlab(level.getBlockState(pos));
    }

    /**
     * A "feet" cell is a valid standing spot: 2 cells of clearance above a
     * solid floor.
     */
    public static boolean isStandable(BlockGetter level, BlockPos feet) {
        return canWalkOn(level, feet.below())
                && canWalkThrough(level, feet)
                && canWalkThrough(level, feet.above());
    }

    /**
     * Is this block a hazard the bot must never stand in / next to break?
     * Lava and fire are hard hazards; we keep it minimal for the MVP.
     */
    public static boolean isHazard(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            // Lava is the dangerous fluid. Water we simply don't enter (above),
            // but it isn't a damage hazard.
            return fluid.getType().getBucket() == net.minecraft.world.item.Items.LAVA_BUCKET;
        }
        // The non-fluid hazard set — blocks we must never
        // path into (magma included: it damages on contact).
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
    }

    /**
     * A cell we shouldn't walk/sprint
     * INTO — ANY fluid (incl. water, unlike {@link #isHazard}) plus the same block set.
     * Used for "is it safe to keep moving into the cell ahead" checks (walk-while-break
     * suppressor, descend safeMode), where even water counts (the current shoves us).
     */
    public static boolean avoidWalkingInto(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true;
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.END_PORTAL)
