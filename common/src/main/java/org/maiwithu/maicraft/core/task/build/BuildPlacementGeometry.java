// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.core.build.BuildValidity;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pure, read-only placement geometry shared by build preflight and live execution.
 *
 * <p>A gesture is deliberately more concrete than a desired block state: it freezes a feet cell,
 * the block face to click, and the point on that face.  The task still has to walk there, turn the
 * real camera, raytrace the real crosshair and submit a native use receipt.  This class merely
 * proves before construction starts that at least one such first-person gesture can express the
 * requested authored state.</p>
 *
 * <p>For ordinary and modded {@link BlockItem}s the proof asks the item/block itself through
 * {@code getStateForPlacement}; it does not maintain a block whitelist.  The small fallback for a
 * standing/wall item is needed only when its future support is itself part of the still-unbuilt
 * plan, so the live level quite correctly refuses the hypothetical placement during preflight.</p>
 */
final class BuildPlacementGeometry {

    private static final double CROUCH_EYE_HEIGHT = 1.27;
    private static final double REACH = 4.45;
    private static final double[] FACE_SAMPLES = {0.25, 0.50, 0.75};
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP
    };

    record Gesture(BlockPos stance, BlockPos clicked, Direction face, Vec3 point,
                   float yaw, float pitch, String proof) {
        Gesture {
            stance = stance.immutable();
            clicked = clicked.immutable();
        }

        BlockHitResult syntheticHit() {
            return new BlockHitResult(point, face, clicked, false);
        }
    }

    record GeneratedCell(BlockPos pos, BlockState expected) {
        GeneratedCell {
            pos = pos.immutable();
        }
    }

    private BuildPlacementGeometry() {}

    /** Secondary halves are verified as effects of their primary item use, never clicked again. */
    static BlockPos primaryOf(BuildTaskRecord.Target target) {
        BlockState desired = target.desiredState();
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return target.pos().below();
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return target.pos().relative(
                    desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        }
        return target.pos();
    }

    /** Cells a vanilla one-item placement creates in addition to the primary cell. */
    static List<GeneratedCell> generatedBy(BuildTaskRecord.Target target) {
        BlockState desired = target.desiredState();
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return List.of(new GeneratedCell(target.pos().above(),
                    desired.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return List.of(new GeneratedCell(target.pos().relative(facing),
                    desired.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
        }
        return List.of();
    }

    /**
     * Enumerate bounded, physically achievable gestures.  The returned list is immutable and may
     * safely be retained by a task across ticks; it contains no player/world/context reference.
     */
    static List<Gesture> plan(LocalPlayer player, BuildTaskRecord.Target target,
                              Map<Long, BuildTaskRecord.Target> targets) {
        return plan(player, target, targets, false, ignored -> true);
    }

    /** Cheap preflight proof; a negative result still exhausts the complete finite search. */
    static boolean hasAnyGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                 Map<Long, BuildTaskRecord.Target> targets) {
        return !plan(player, target, targets, true, ignored -> true).isEmpty();
    }

    /** Same placement proof, restricted to caller-approved physical stance cells. */
    static boolean hasAnyGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                 Map<Long, BuildTaskRecord.Target> targets,
                                 Predicate<BlockPos> stanceAllowed) {
        return !plan(player, target, targets, true, stanceAllowed).isEmpty();
    }

    private static List<Gesture> plan(LocalPlayer player, BuildTaskRecord.Target target,
                                      Map<Long, BuildTaskRecord.Target> targets, boolean firstOnly,
                                      Predicate<BlockPos> stanceAllowed) {
        if (!(target.item() instanceof BlockItem)) {
            return List.of();
        }
        List<Gesture> out = new ArrayList<>();
        BlockPos placeAt = target.pos();
        BuildPlacementStage stage = new BuildPlacementStage(player.level(), player.level()::isLoaded,
                targets, target, firstOnly);

        // Replaceable blocks with their own outline (grass, snow layers, an existing slab being
        // doubled) can be clicked directly.  Adjacent support faces cover ordinary placement.
        BlockState live = player.level().isLoaded(placeAt)
                ? player.level().getBlockState(placeAt) : null;
        if (live != null && !live.isAir()
                && (live.canBeReplaced()
                || (live.is(target.block()) && maximumUses(target) > 1))) {
            enumerateForClicked(player, target, stage, placeAt, Direction.UP, true,
                    firstOnly, stanceAllowed, out);
            if (firstOnly && !out.isEmpty()) return List.copyOf(out);
        }
        for (Direction towardSupport : SUPPORT_ORDER) {
            BlockPos clicked = placeAt.relative(towardSupport);
            Direction face = towardSupport.getOpposite();
            if (!stage.support(clicked, face)) continue;
            enumerateForClicked(player, target, stage, clicked, face, false,
                    firstOnly, stanceAllowed, out);
            if (firstOnly && !out.isEmpty()) break;
        }
        return List.copyOf(out);
    }

    /** Runtime prediction uses the actual crosshair hit and current camera rotation. */
    static BlockState predict(LocalPlayer player, BuildTaskRecord.Target target,
                              BlockHitResult hit, float yaw, float pitch) {
        if (!(target.item() instanceof BlockItem)) return null;
        return predictedState(player, new ItemStack(target.item()), hit, yaw, pitch);
    }

    /** True for the final state or for a receipt-confirmable intermediate of a bounded multi-use. */
    static boolean isProgress(BuildTaskRecord.Target target, BlockState before, BlockState after) {
        if (target.matches(after)) return true;
        // Machine-authored custom state must not be reinterpreted as a generic accumulation step.
        if (!target.matchesExactProperties(after)) return false;
        BlockState desired = target.desiredState();
        if (after.getBlock() != desired.getBlock()) return false;

        if (desired.getBlock() instanceof SlabBlock
                && desired.hasProperty(BlockStateProperties.SLAB_TYPE)
                && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return !after.isAir() && (!before.is(after.getBlock())
                    || (before.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && before.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE));
        }

        // Snow layers, candles, eggs, pickles and modded stackable blocks expose the same monotone
        // integer shape.  A confirmed use may advance one step; it may never overshoot or regress.
        boolean advanced = false;
        for (var property : desired.getProperties()) {
            if (!(property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty p)
                    || !after.hasProperty(p)) continue;
            int want = desired.getValue(p);
            int now = after.getValue(p);
            int was = before.hasProperty(p) ? before.getValue(p) : p.getPossibleValues().stream()
                    .min(Integer::compareTo).orElse(0) - 1;
            if (now > want || now < was) return false;
            advanced |= now > was;
        }
        return advanced && authoredPropertiesCompatibleExceptProgress(target, after);
    }

    static int maximumUses(BuildTaskRecord.Target target) {
        if (target.desiredState().getBlock() instanceof SlabBlock
                && target.desiredState().hasProperty(BlockStateProperties.SLAB_TYPE)
                && target.desiredState().getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        return Math.max(1, target.materialCount());
    }

    private static void enumerateForClicked(LocalPlayer player, BuildTaskRecord.Target target,
                                             BuildPlacementStage stage,
                                             BlockPos clicked, Direction face, boolean direct,
                                             boolean firstOnly,
                                             Predicate<BlockPos> stanceAllowed,
                                             List<Gesture> out) {
        BlockPos placeAt = target.pos();
        for (BlockPos stance : candidateStances(placeAt)) {
            if (firstOnly && !out.isEmpty()) return;
            if (!stanceAllowed.test(stance)
                    || !stage.bodyCellAvailable(stance)) continue;
            Vec3 eye = new Vec3(stance.getX() + 0.5, stance.getY() + CROUCH_EYE_HEIGHT,
                    stance.getZ() + 0.5);
            for (double a : FACE_SAMPLES) {
                for (double b : FACE_SAMPLES) {
                    Vec3 point = facePoint(clicked, face, a, b);
                    if (eye.distanceToSqr(point) > REACH * REACH) continue;
                    if (!stage.rayClear(eye, point, clicked)) continue;
                    float yaw = AimGeometry.yawTo(eye, point);
                    float pitch = AimGeometry.pitchTo(eye, point);
                    Gesture gesture = new Gesture(stance, clicked, face, point, yaw, pitch,
                            direct ? "replaceable target face" : "adjacent support face");
                    if (provesGesture(player, target, gesture)) {
                        out.add(gesture);
                        if (firstOnly) return;
                    }
                }
            }
        }
    }

    private static boolean provesGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                         Gesture gesture) {
        if (target.itemPlace()) return true;
        BlockState predicted = predict(player, target, gesture.syntheticHit(),
                gesture.yaw(), gesture.pitch());
        if (predicted != null && (target.acceptsPlacedState(predicted)
                || isProgress(target, player.level().isLoaded(target.pos())
                        ? player.level().getBlockState(target.pos())
                        : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), predicted))) {
            return true;
        }

        // If every possible state of this block satisfies the authored-state contract, the exact
        // orientation is intentionally irrelevant (lantern hanging/waterlogged and similar world
        // properties fall here).  This remains generic for mod blocks.
        if (target.item() instanceof BlockItem blockItem
                && blockItem.getBlock() == target.block()
                && acceptsEveryState(target)) {
            return true;
        }

        // A standing/wall item may return null solely because its planned support does not exist
        // yet.  The clicked outward face is exactly the authored wall-facing property.
        if (target.item() instanceof StandingAndWallBlockItem
                && gesture.face().getAxis().isHorizontal()
                && target.exactProperties().isEmpty()) {
            BlockState desired = target.desiredState();
            if (desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    && desired.getValue(BlockStateProperties.HORIZONTAL_FACING) == gesture.face()) {
                return true;
            }
            return desired.hasProperty(BlockStateProperties.FACING)
                    && desired.getValue(BlockStateProperties.FACING) == gesture.face();
        }
        return false;
    }

    private static boolean acceptsEveryState(BuildTaskRecord.Target target) {
        for (BlockState possible : target.block().getStateDefinition().getPossibleStates()) {
            if (!target.acceptsPlacedState(possible)) return false;
        }
        return true;
    }

    private static boolean authoredPropertiesCompatibleExceptProgress(
            BuildTaskRecord.Target target, BlockState state) {
        BlockState desired = target.desiredState();
        BlockState adjusted = state;
        for (var property : desired.getProperties()) {
            if (property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty p
                    && adjusted.hasProperty(p)) {
                adjusted = adjusted.setValue(p, desired.getValue(p));
            }
        }
        return BuildValidity.sameBlockState(adjusted, desired);
    }

    static List<BlockPos> candidateStances(BlockPos target) {
        List<BlockPos> out = new ArrayList<>();
        for (int dy : new int[]{-1, 0, -2, 1}) {
            int y = target.getY() + dy;
            for (int radius : new int[]{2, 3, 4}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        out.add(new BlockPos(target.getX() + dx, y, target.getZ() + dz));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(target)));
        return out;
    }

    private static Vec3 facePoint(BlockPos clicked, Direction face, double a, double b) {
        double x = clicked.getX() + 0.5;
        double y = clicked.getY() + 0.5;
        double z = clicked.getZ() + 0.5;
        switch (face.getAxis()) {
            case X -> {
                x = clicked.getX() + (face == Direction.EAST ? 1.0 : 0.0);
                y = clicked.getY() + a;
                z = clicked.getZ() + b;
            }
            case Y -> {
                x = clicked.getX() + a;
                y = clicked.getY() + (face == Direction.UP ? 1.0 : 0.0);
                z = clicked.getZ() + b;
            }
            case Z -> {
                x = clicked.getX() + a;
                y = clicked.getY() + b;
                z = clicked.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
            }
        }
        // Pull a hair inside the clicked block so floating-point rounding keeps the ray on the
        // intended face while the BlockHitResult still reports that exact outward direction.
        return new Vec3(x - face.getStepX() * 1.0e-4,
                y - face.getStepY() * 1.0e-4,
                z - face.getStepZ() * 1.0e-4);
    }

    private static BlockState predictedState(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                             float yaw, float pitch) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        Vec3 look = direction(yaw, pitch);
        Direction[] nearest = Direction.values();
        Arrays.sort(nearest, Comparator.comparingDouble(direction ->
                -(direction.getStepX() * look.x
                        + direction.getStepY() * look.y
                        + direction.getStepZ() * look.z)));
        try {
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}) {
                @Override public Direction getHorizontalDirection() {
                    return Direction.fromYRot(yaw);
                }
                @Override public float getRotation() { return yaw; }
                @Override public Direction getNearestLookingDirection() {
                    return Direction.getNearest(look.x, look.y, look.z);
                }
                @Override public Direction getNearestLookingVerticalDirection() {
                    return look.y >= 0.0 ? Direction.UP : Direction.DOWN;
                }
                @Override public Direction[] getNearestLookingDirections() {
                    return nearest.clone();
                }
            };
            return blockItem.getBlock().getStateForPlacement(context);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Vec3 direction(float yaw, float pitch) {
        float pitchRad = pitch * ((float) Math.PI / 180.0F);
        float yawRad = -yaw * ((float) Math.PI / 180.0F);
        float cosYaw = net.minecraft.util.Mth.cos(yawRad);
        float sinYaw = net.minecraft.util.Mth.sin(yawRad);
        float cosPitch = net.minecraft.util.Mth.cos(pitchRad);
        float sinPitch = net.minecraft.util.Mth.sin(pitchRad);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }
}
