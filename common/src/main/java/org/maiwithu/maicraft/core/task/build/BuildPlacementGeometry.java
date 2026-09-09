// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.core.build.BuildValidity;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    private static final double REACH = 4.45;
    private static final double[] FACE_SAMPLES = {0.25, 0.50, 0.75};
    private static final double[] SIDE_HEIGHT_SAMPLES = {0.25, 0.375, 0.625, 0.75};
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP
    };
    private static final List<BlockPos> STANCE_OFFSETS = createStanceOffsets();

    record Gesture(BlockPos stance, BlockPos clicked, Direction face, Vec3 point,
                   float yaw, float pitch, boolean sneak, String proof) {
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

    /** A reachable native gesture from the actual feet position, without centering on a path cell. */
    static Gesture currentGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets) {
        return currentGesture(player, target, targets, ignored -> true);
    }

    static Gesture currentGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets, Predicate<Gesture> allowed) {
        if (!(target.item() instanceof BlockItem) || !player.level().isLoaded(target.pos())) return null;
        BuildPlacementStage stage = new BuildPlacementStage(player.level(), player.level()::isLoaded,
                targets, target, false);
        if (!stage.bodyCellAvailable(player.blockPosition())) return null;
        for (AABB box : target.desiredState().getCollisionShape(stage, target.pos()).toAabbs())
            if (box.move(target.pos()).intersects(player.getBoundingBox())) return null;
        List<Gesture> candidates = new ArrayList<>();
        BlockState live = stage.state(target.pos());
        if (!live.isAir() && (live.canBeReplaced()
                || (live.is(target.block()) && maximumUses(target) > 1)))
            gesturesAt(player, target, stage, target.pos(), Direction.UP, player.position(),
                    true, true, false, allowed, candidates);
        for (Direction toward : SUPPORT_ORDER) {
            BlockPos clicked = target.pos().relative(toward);
            Direction face = toward.getOpposite();
            if (stage.support(clicked, face))
                gesturesAt(player, target, stage, clicked, face, player.position(), false, true, false, allowed, candidates);
        }
        return candidates.stream().min(Comparator.comparingDouble(g ->
                Math.abs(net.minecraft.util.Mth.wrapDegrees(g.yaw() - player.getYRot()))
                        + Math.abs(g.pitch() - player.getXRot()))).orElse(null);
    }

    /** A native-state proof from hypothetical feet, using only existing world support and shapes. */
    static Gesture liveGestureFrom(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets, Vec3 feet) {
        return liveGestureFrom(player, target, targets, feet, ignored -> true);
    }

    static Gesture liveGestureFrom(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets, Vec3 feet,
                                   Predicate<Gesture> allowed) {
        if (!(target.item() instanceof BlockItem) || !player.level().isLoaded(target.pos())) return null;
        BuildPlacementStage stage = new BuildPlacementStage(player.level(), player.level()::isLoaded,
                targets, target, false);
        AABB body = player.getBoundingBox().move(feet.subtract(player.position())).deflate(1.0e-5);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(body.minX - 1, body.minY - 1, body.minZ - 1),
                BlockPos.containing(body.maxX + 1, body.maxY + 1, body.maxZ + 1))) {
            BlockState state = stage.state(cell);
            if (!state.getFluidState().isEmpty() && body.intersects(new AABB(cell))) return null;
            for (AABB shape : state.getCollisionShape(stage, cell).toAabbs())
                if (shape.move(cell).intersects(body)) return null;
        }
        for (AABB shape : target.desiredState().getCollisionShape(stage, target.pos()).toAabbs())
            if (shape.move(target.pos()).intersects(body)) return null;
        List<Gesture> out = new ArrayList<>(1);
        BlockState live = stage.state(target.pos());
        if (!live.isAir() && (live.canBeReplaced() || (live.is(target.block()) && maximumUses(target) > 1))) {
            gesturesAt(player, target, stage, target.pos(), Direction.UP, feet, true, true, true, allowed, out);
            if (!out.isEmpty()) return out.getFirst();
        }
        for (Direction toward : SUPPORT_ORDER) {
            BlockPos clicked = target.pos().relative(toward);
            if (!stage.support(clicked, toward.getOpposite())) continue;
            gesturesAt(player, target, stage, clicked, toward.getOpposite(), feet, false, true, true, allowed, out);
            if (!out.isEmpty()) return out.getFirst();
        }
        return null;
    }

    private static List<Gesture> plan(LocalPlayer player, BuildTaskRecord.Target target,
                                      Map<Long, BuildTaskRecord.Target> targets, boolean firstOnly,
                                      Predicate<BlockPos> stanceAllowed) {
        // Compatibility for synchronous utility probes and standalone callers. Construction
        // retains PlanSearch across ticks and must never drain this adapter on the client thread.
        var search = new PlanSearch(player, target, targets, firstOnly, firstOnly, stanceAllowed);
        while (!search.advance(Integer.MAX_VALUE).complete()) { }
        return search.results();
    }

    record PlanProgress(boolean complete, int probeCount, int gestureCount, int stanceChecks) {}

    /** Complete finite enumeration, yielding between individual face probes instead of whole targets. */
    static final class PlanSearch {
        private static final long SLICE_NANOS = 4_000_000;
        private final LocalPlayer player;
        private final BuildTaskRecord.Target target;
        private final BuildPlacementStage stage;
        private final boolean firstOnly;
        private final Predicate<BlockPos> stanceAllowed;
        private final List<Gesture> found = new ArrayList<>();
        private FaceProbe support;
        private List<AABB> boxes = List.of();
        private Vec3 feet;
        private int supportAt, stanceAt = STANCE_OFFSETS.size(), pointAt, pointCount, probeCount, stanceChecks;
        private boolean complete;

        PlanSearch(LocalPlayer player, BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> targets) {
            this(player, target, targets, false, false, ignored -> true);
        }

        PlanSearch(LocalPlayer player, BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> targets,
                   boolean preflight, boolean firstOnly, Predicate<BlockPos> stanceAllowed) {
            this.player = player; this.target = target; this.firstOnly = firstOnly; this.stanceAllowed = stanceAllowed;
            stage = new BuildPlacementStage(player.level(), player.level()::isLoaded, targets, target, preflight);
            complete = !(target.item() instanceof BlockItem);
        }

        PlanProgress advance(int workBudget) {
            long deadline = System.nanoTime() + SLICE_NANOS;
            for (int work = 0; work < Math.max(0, workBudget) && !complete && System.nanoTime() < deadline; work++) {
                if (pointAt < pointCount) {
                    int sample = pointAt++;
                    double[] first = firstSamples(support.face()), second = secondSamples(support.face());
                    int perBox = first.length * second.length;
                    Vec3 point = facePoint(support.clicked(), boxes.get(sample / perBox), support.face(),
                            first[(sample % perBox) / second.length], second[sample % second.length]);
                    probeCount++;
                    var gesture = gestureAtPoint(player, target, stage, support, feet, point, false, ignored -> true);
                    if (gesture != null) { found.add(gesture); if (firstOnly) complete = true; }
                } else if (stanceAt < STANCE_OFFSETS.size()) {
                    BlockPos stance = target.pos().offset(STANCE_OFFSETS.get(stanceAt++)); stanceChecks++;
                    if (!stanceAllowed.test(stance) || !stage.bodyCellAvailable(stance)) continue;
                    feet = Vec3.atBottomCenterOf(stance); pointAt = 0;
                    pointCount = boxes.size() * firstSamples(support.face()).length * secondSamples(support.face()).length;
                } else if (supportAt <= SUPPORT_ORDER.length) {
                    boolean direct = supportAt == 0;
                    Direction toward = direct ? Direction.DOWN : SUPPORT_ORDER[supportAt - 1]; supportAt++;
                    BlockPos clicked = direct ? target.pos() : target.pos().relative(toward);
                    if (direct) {
                        BlockState live = player.level().isLoaded(clicked) ? player.level().getBlockState(clicked) : null;
                        if (live == null || live.isAir() || !(live.canBeReplaced()
                                || live.is(target.block()) && maximumUses(target) > 1)) continue;
                    } else if (!stage.support(clicked, toward.getOpposite())) continue;
                    support = faceProbe(stage, clicked, toward.getOpposite(), direct);
                    boxes = support.shape().toAabbs(); stanceAt = boxes.isEmpty() ? STANCE_OFFSETS.size() : 0;
                } else complete = true;
            }
            return new PlanProgress(complete, probeCount, found.size(), stanceChecks);
        }

        List<Gesture> results() {
            if (!complete) throw new IllegalStateException("placement enumeration is still pending");
            return List.copyOf(found);
        }
    }

    /** Runtime prediction uses the actual crosshair hit and current camera rotation. */
    static BlockState predict(LocalPlayer player, BuildTaskRecord.Target target,
                              BlockHitResult hit, float yaw, float pitch) {
        if (!(target.item() instanceof BlockItem)) return null;
        return predictedState(player, new ItemStack(target.item()), hit, yaw, pitch,
                player.isSecondaryUseActive(), target.pos());
    }

    /** True for the final state or for a receipt-confirmable intermediate of a bounded multi-use. */
    static boolean isProgress(BuildTaskRecord.Target target, BlockState before, BlockState after) {
        if (after.equals(before)) return false;
        if (target.matches(after)) return true;
        // Machine-authored custom state must not be reinterpreted as a generic accumulation step.
        if (!target.matchesExactProperties(after)) return false;
        BlockState desired = target.desiredState();
        if (after.getBlock() != desired.getBlock()) return false;

        if (desired.getBlock() instanceof SlabBlock
                && desired.hasProperty(BlockStateProperties.SLAB_TYPE)
                && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return !before.is(after.getBlock());
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

    private static void gesturesAt(LocalPlayer player, BuildTaskRecord.Target target,
                                    BuildPlacementStage stage, BlockPos clicked, Direction face,
                                    Vec3 feet, boolean direct, boolean live, List<Gesture> out) {
        gesturesAt(player, target, stage, clicked, face, feet, direct, live, false, ignored -> true, out);
    }

    private static void gesturesAt(LocalPlayer player, BuildTaskRecord.Target target,
                                    BuildPlacementStage stage, BlockPos clicked, Direction face,
                                    Vec3 feet, boolean direct, boolean live, boolean firstOnly,
                                    Predicate<Gesture> allowed, List<Gesture> out) {
        FaceProbe probe = faceProbe(stage, clicked, face, direct);
        for (Vec3 point : facePoints(clicked, probe.shape(), face)) {
            Gesture gesture = gestureAtPoint(player, target, stage, probe, feet, point, live, allowed);
            if (gesture != null) {
                out.add(gesture);
                if (firstOnly) return;
            }
        }
    }

    private record FaceProbe(BlockPos clicked, Direction face, boolean direct, boolean sneak, VoxelShape shape) {}

    private static FaceProbe faceProbe(BuildPlacementStage stage, BlockPos clicked, Direction face, boolean direct) {
        BlockState state = stage.state(clicked);
        return new FaceProbe(clicked, face, direct, BuildPlacementInteraction.requiresSneak(state), state.getShape(stage, clicked));
    }

    private static Gesture gestureAtPoint(LocalPlayer player, BuildTaskRecord.Target target,
                                         BuildPlacementStage stage, FaceProbe probe, Vec3 feet, Vec3 point,
                                         boolean live, Predicate<Gesture> allowed) {
        Vec3 eye = feet.add(0, player.getEyeHeight(probe.sneak() ? Pose.CROUCHING : Pose.STANDING), 0);
        if (eye.distanceToSqr(point) > REACH * REACH) return null;
        float yaw = AimGeometry.yawTo(eye, point), pitch = AimGeometry.pitchTo(eye, point);
        Gesture gesture = new Gesture(BlockPos.containing(feet), probe.clicked(), probe.face(), point, yaw, pitch,
                probe.sneak(), probe.direct() ? "replaceable target face" : "adjacent support face");
        if (!allowed.test(gesture)) return null;
        if (!stage.rayClear(eye, point, probe.clicked())) return null;
        BlockHitResult hit = probe.shape().clip(eye, point, probe.clicked());
        if (hit == null || hit.isInside() || hit.getDirection() != probe.face()
                || hit.getLocation().distanceToSqr(point) > 1.0e-6) return null;
        return (live ? provesLiveGesture(player, target, gesture) : provesGesture(player, target, gesture)) ? gesture : null;
    }

    private static boolean provesLiveGesture(LocalPlayer player, BuildTaskRecord.Target target, Gesture gesture) {
        BlockState predicted = predictedState(player, new ItemStack(target.item()), gesture.syntheticHit(),
                gesture.yaw(), gesture.pitch(), gesture.sneak(), target.pos());
        return predicted != null && (target.acceptsPlacedState(predicted)
                || isProgress(target, player.level().getBlockState(target.pos()), predicted));
    }

    private static boolean provesGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                         Gesture gesture) {
        NativePlacement placement = predictPlacement(player, new ItemStack(target.item()), gesture.syntheticHit(),
                gesture.yaw(), gesture.pitch(), gesture.sneak(), target.pos());
        if (placement != null && !placement.pos().equals(target.pos())) return false;
        BlockState predicted = placement == null ? null : placement.state();
        if (predicted != null && (target.itemPlace() || target.acceptsPlacedState(predicted)
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
        return STANCE_OFFSETS.stream().map(target::offset).toList();
    }

    private static List<BlockPos> createStanceOffsets() {
        List<BlockPos> out = new ArrayList<>();
        for (int dy : new int[]{-1, 0, -2, 1}) {
            for (int radius : new int[]{1, 2, 3, 4}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        out.add(new BlockPos(dx, dy, dz));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(BlockPos.ZERO)));
        return List.copyOf(out);
    }

    static List<Vec3> facePoints(BlockPos clicked, VoxelShape shape, Direction face) {
        // Clip against the union from the actual eye later: hidden/internal box faces cannot win.
        List<Vec3> points = new ArrayList<>();
        for (AABB box : shape.toAabbs()) for (double a : firstSamples(face)) for (double b : secondSamples(face))
            points.add(facePoint(clicked, box, face, a, b));
        return points;
    }

    private static double[] firstSamples(Direction face) {
        return face.getAxis() == Direction.Axis.X ? SIDE_HEIGHT_SAMPLES : FACE_SAMPLES;
    }

    private static double[] secondSamples(Direction face) {
        return face.getAxis() == Direction.Axis.Z ? SIDE_HEIGHT_SAMPLES : FACE_SAMPLES;
    }

    private static Vec3 facePoint(BlockPos clicked, AABB box, Direction face, double a, double b) {
        double x = 0, y = 0, z = 0;
        switch (face.getAxis()) {
            case X -> {
                x = face == Direction.EAST ? box.maxX : box.minX;
                y = box.minY + (box.maxY - box.minY) * a;
                z = box.minZ + (box.maxZ - box.minZ) * b;
            }
            case Y -> {
                x = box.minX + (box.maxX - box.minX) * a;
                y = face == Direction.UP ? box.maxY : box.minY;
                z = box.minZ + (box.maxZ - box.minZ) * b;
            }
            case Z -> {
                x = box.minX + (box.maxX - box.minX) * a;
                y = box.minY + (box.maxY - box.minY) * b;
                z = face == Direction.SOUTH ? box.maxZ : box.minZ;
            }
        }
        // Pull a hair inside the clicked block so floating-point rounding keeps the ray on the
        // intended face while the BlockHitResult still reports that exact outward direction.
        return new Vec3(clicked.getX() + x - face.getStepX() * 1.0e-4,
                clicked.getY() + y - face.getStepY() * 1.0e-4,
                clicked.getZ() + z - face.getStepZ() * 1.0e-4);
    }

    private static BlockState predictedState(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                             float yaw, float pitch, boolean sneak, BlockPos target) {
        NativePlacement placement = predictPlacement(player, stack, hit, yaw, pitch, sneak, target);
        return placement != null && placement.pos().equals(target) ? placement.state() : null;
    }

    private record NativePlacement(BlockPos pos, BlockState state) {}

    private static NativePlacement predictPlacement(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                                     float yaw, float pitch, boolean sneak, BlockPos target) {
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
                @Override public boolean isSecondaryUseActive() { return sneak; }
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
            // The native item can replace the clicked support instead of placing beside it
            // (e.g. merging the same slab). A correct state at that other cell proves nothing here.
            BlockPos destination = context.getClickedPos();
            return new NativePlacement(destination, destination.equals(target)
                    ? blockItem.getBlock().getStateForPlacement(context) : null);
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
