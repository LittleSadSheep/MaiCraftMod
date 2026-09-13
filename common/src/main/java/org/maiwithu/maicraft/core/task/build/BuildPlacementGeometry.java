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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 为每个施工格寻找能从第一人称完成的点击：人站在哪里、点哪块的哪个面、看向哪里，以及是否要蹲下。
 * 这里检查距离、身体空间、视线和原版预计放置状态，不发送右键；执行器之后还要实际转头并等操作确认。
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

    // 遇到床头或门上半时，找到真正需要主动放下的床脚或下半坐标。
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

    // 列出这次放置会顺带生成的另一半，供保护检查和操作后的确认共同使用。这里只识别已列出的两格结构。
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

    /** 同步枚举这一格的点击候选，返回不可变列表；可留到后续使用，但执行时仍要重新检查现场。 */
    static List<Gesture> plan(LocalPlayer player, BuildTaskRecord.Target target,
                              Map<Long, BuildTaskRecord.Target> targets) {
        return plan(player, target, targets, false, ignored -> true);
    }

    /** 预检找到一个候选就返回；若没有候选，会同步查完整个有限范围，不能保证耗时很短。 */
    static boolean hasAnyGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                 Map<Long, BuildTaskRecord.Target> targets) {
        return !plan(player, target, targets, true, ignored -> true).isEmpty();
    }

    /** 同样的预检，但只允许调用者认可的站位；这份额外条件会直接影响能否找到候选。 */
    static boolean hasAnyGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                 Map<Long, BuildTaskRecord.Target> targets,
                                 Predicate<BlockPos> stanceAllowed) {
        return !plan(player, target, targets, true, stanceAllowed).isEmpty();
    }

    /** 使用角色真实脚下位置的入口，不要求先移到某格中心。 */
    static Gesture currentGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets) {
        return currentGesture(player, target, targets, ignored -> true);
    }

    // 从角色现在的真实脚高找点击方案，允许站在半砖上；多个方案都成立时，优先选转头幅度小的。
    static Gesture currentGesture(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets, Predicate<Gesture> allowed) {
        if (!(target.item() instanceof BlockItem) || !player.level().isLoaded(target.pos())) return null;
        BuildPlacementStage stage = new BuildPlacementStage(player.level(), player.level()::isLoaded,
                targets, target, false);
        if (!bodyClearFrom(player, target, stage, player.position())) return null;
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

    /** 试算给定站位的原版放置结果，支撑和遮挡只读当前世界。 */
    static Gesture liveGestureFrom(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets, Vec3 feet) {
        return liveGestureFrom(player, target, targets, feet, ignored -> true);
    }

    // 从给定的真实站位找第一个可行点击，供一处站位连放多格使用；不一定选转头最少的方案。
    static Gesture liveGestureFrom(LocalPlayer player, BuildTaskRecord.Target target,
                                   Map<Long, BuildTaskRecord.Target> targets, Vec3 feet,
                                   Predicate<Gesture> allowed) {
        if (!(target.item() instanceof BlockItem) || !player.level().isLoaded(target.pos())) return null;
        BuildPlacementStage stage = new BuildPlacementStage(player.level(), player.level()::isLoaded,
                targets, target, false);
        if (!bodyClearFrom(player, target, stage, feet)) return null;
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

    // 把角色当前身体盒平移到候选脚下，检查周围真实碰撞和流体，再检查目标方块放下后不会卡住身体。
    private static boolean bodyClearFrom(LocalPlayer player, BuildTaskRecord.Target target,
                                         BuildPlacementStage stage, Vec3 feet) {
        AABB body = player.getBoundingBox().move(feet.subtract(player.position())).deflate(1.0e-5);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(body.minX - 1, body.minY - 1, body.minZ - 1),
                BlockPos.containing(body.maxX + 1, body.maxY + 1, body.maxZ + 1))) {
            BlockState state = stage.state(cell);
            if (!state.getFluidState().isEmpty() && body.intersects(new AABB(cell))) return false;
            for (AABB shape : state.getCollisionShape(stage, cell).toAabbs())
                if (shape.move(cell).intersects(body)) return false;
        }
        for (AABB shape : target.desiredState().getCollisionShape(stage, target.pos()).toAabbs())
            if (shape.move(target.pos()).intersects(body)) return false;
        return true;
    }

    /** A hypothetical support view can prove access, but never replaces the live placement/acknowledgement gate. */
    static Gesture projectedGestureFrom(LocalPlayer player, BuildTaskRecord.Target target,
                                         net.minecraft.world.level.BlockGetter projected,
                                         Predicate<BlockPos> loaded, Vec3 feet) {
        if (!(target.item() instanceof BlockItem) || !loaded.test(target.pos())) return null;
        var stage = new BuildPlacementStage(projected, loaded, Map.of(), target, false, true);
        if (!bodyClearFrom(player, target, stage, feet)) return null;
        AABB body = player.getBoundingBox().move(feet.subtract(player.position())).deflate(1.0e-5);
        for (GeneratedCell effect : generatedBy(target))
            for (AABB shape : effect.expected().getCollisionShape(stage, effect.pos()).toAabbs())
                if (shape.move(effect.pos()).intersects(body)) return null;
        var out = new ArrayList<Gesture>(1);
        for (Direction toward : SUPPORT_ORDER) {
            BlockPos clicked = target.pos().relative(toward);
            if (!stage.support(clicked, toward.getOpposite())) continue;
            gesturesAt(player, target, stage, clicked, toward.getOpposite(), feet,
                    false, true, true, ignored -> true, out);
            if (!out.isEmpty()) return out.getFirst();
        }
        return null;
    }

    private static List<Gesture> plan(LocalPlayer player, BuildTaskRecord.Target target,
                                      Map<Long, BuildTaskRecord.Target> targets, boolean firstOnly,
                                      Predicate<BlockPos> stanceAllowed) {
        // 供仍要求同步结果的辅助检查使用；正常施工保存 PlanSearch，让后续游戏更新接着推进。
        var search = new PlanSearch(player, target, targets, firstOnly, firstOnly, stanceAllowed);
        // 这是同步兼容入口：内部虽然分片检查，但在这里立即接着跑到结束，不会真的等下一刻。
        while (!search.advance(Integer.MAX_VALUE).complete()) { }
        return search.results();
    }

    record PlanProgress(boolean complete, int probeCount, int gestureCount, int stanceChecks) {}

    // 保存“哪个支撑面、哪个站位、面上的哪个点”三个进度。实际施工可每刻只推进少量检查，避免从头反复找。
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

        // 一项工作只推进一个取样点、一个站位或一个支撑面；最多约四毫秒，下次从剩余进度继续。
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
                // 批量预检使用格子底部中心作脚下位置；半格高度的当前站位另由 currentGesture 处理。
                } else if (stanceAt < STANCE_OFFSETS.size()) {
                    BlockPos stance = target.pos().offset(STANCE_OFFSETS.get(stanceAt++)); stanceChecks++;
                    if (!stanceAllowed.test(stance) || !stage.bodyCellAvailable(stance)) continue;
                    feet = Vec3.atBottomCenterOf(stance); pointAt = 0;
                    pointCount = boxes.size() * firstSamples(support.face()).length * secondSamples(support.face()).length;
                } else if (supportAt <= SUPPORT_ORDER.length) {
                    // 先试直接点目标格里的可覆盖方块或同类可叠加方块，再试目标周围六个支撑面。
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

        // 全部枚举结束后才允许取最终列表，避免调用者把尚未找完误认为没有方案。
        List<Gesture> results() {
            if (!complete) throw new IllegalStateException("placement enumeration is still pending");
            return found.stream().sorted(BuildPlacementPreference.gestures(player.position(), target)).toList();
        }
    }

    /** 执行前用真实准星落点、视角和当前蹲下状态，再试算一次原版放置结果。 */
    static BlockState predict(LocalPlayer player, BuildTaskRecord.Target target,
                              BlockHitResult hit, float yaw, float pitch) {
        if (!(target.item() instanceof BlockItem)) return null;
        return predictedState(player, new ItemStack(target.item()), hit, yaw, pitch,
                player.isSecondaryUseActive(), target.pos());
    }

    // 确认多次点击中的一次是否向目标靠近：状态必须有变化；已完成直接通过，双层半砖允许先放第一片。
    // 数量属性必须不倒退、不超过目标，并至少一项增加。新最终属性规则允许中间数量；旧入口仍受前置精确比较限制。
    static boolean isProgress(BuildTaskRecord.Target target, BlockState before, BlockState after) {
        if (after.equals(before)) return false;
        if (placementComplete(target, after)) return true;
        // 只有旧入口继续先查全部精确属性；它仍可能把“最终四根蜡烛”误当成第一下就要四根。
        if (target.finalProperties() == null && !target.matchesExactProperties(after)) return false;
        BlockState desired = target.desiredState();
        if (after.getBlock() != desired.getBlock()) return false;
        if (target.finalProperties() != null && !authoredPropertiesCompatibleExceptProgress(target, after)) return false;

        if (desired.getBlock() instanceof SlabBlock
                && desired.hasProperty(BlockStateProperties.SLAB_TYPE)
                && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return !before.is(after.getBlock());
        }

        // 雪、蜡烛等可能要逐次增加；这里统一按整数属性判断，不逐个识别哪些模组整数确实代表数量。
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

    // 双层半砖最多需要两次使用，其他目标按材料数量估计，至少为一次；它不是成功次数。
    static int maximumUses(BuildTaskRecord.Target target) {
        if (target.desiredState().getBlock() instanceof SlabBlock
                && target.desiredState().hasProperty(BlockStateProperties.SLAB_TYPE)
                && target.desiredState().getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        return Math.max(1, target.materialCount());
    }

    /**
     * 连续放置正在进行时，雪层、蜡烛等数量必须达到目标才离开这一格；第一片半砖也不能提前结束双层半砖的放置。
     */
    static boolean placementComplete(BuildTaskRecord.Target target, BlockState live) {
        if (target.finalProperties() == null) return target.matches(live);
        if (!target.acceptsPlacedState(live)) return false;
        for (var property : List.of(BlockStateProperties.LAYERS, BlockStateProperties.CANDLES,
                BlockStateProperties.PICKLES, BlockStateProperties.EGGS)) {
            if (target.desiredState().hasProperty(property) && target.finalProperties().contains(property.getName())
                    && !live.getValue(property).equals(target.desiredState().getValue(property))) return false;
        }
        return true;
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

    // 先按站立或蹲下眼高算角度，要求点击点在 4.45 格内、未被失败记录排除、视线通畅且真的命中所选面。
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
        if (stage.projectedSupport()) {
            if (!stage.state(target.pos()).isAir() || !probe.clicked().relative(probe.face()).equals(target.pos())) return null;
            NativePlacement predicted = predictPlacement(player, new ItemStack(target.item()), gesture.syntheticHit(),
                    yaw, pitch, gesture.sneak(), target.pos(), stage.proposedAt(probe.clicked()));
            return predicted != null && predicted.state() != null && (target.acceptsPlacedState(predicted.state())
                    || isProgress(target, stage.state(target.pos()), predicted.state()))
                    && projectedPlacementClear(player, target, stage, feet, predicted.state()) ? gesture : null;
        }
        return (live ? provesLiveGesture(player, target, gesture) : provesGesture(player, target, gesture)) ? gesture : null;
    }

    private static boolean projectedPlacementClear(LocalPlayer player, BuildTaskRecord.Target target,
                                                    BuildPlacementStage stage, Vec3 feet, BlockState predicted) {
        double half = player.getBbWidth() / 2;
        double height = Math.max(player.getBbHeight(), player.getDimensions(Pose.STANDING).height());
        AABB body = new AABB(feet.x - half, feet.y, feet.z - half,
                feet.x + half, feet.y + height, feet.z + half).deflate(1.0e-5);
        for (AABB box : predicted.getCollisionShape(stage, target.pos()).toAabbs())
            if (box.move(target.pos()).intersects(body)) return false;
        var actual = generatedBy(new BuildTaskRecord.Target(predicted, target.item(), target.pos(),
                target.label(), null, null, null));
        var expected = generatedBy(target);
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            var effect = actual.get(i);
            if (!effect.pos().equals(expected.get(i).pos())) return false;
            for (AABB box : effect.expected().getCollisionShape(stage, effect.pos()).toAabbs())
                if (box.move(effect.pos()).intersects(body)) return false;
        }
        return true;
    }

    // 现场方案必须能预测出落在目标格的状态，并达到目标或形成允许的中间进度。
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

        // 预检的现场可能还没有计划支撑。如果目标接受这种方块的所有状态，即使暂时预测不出，也保留候选。
        // 这只是预检放宽，执行前还要由现场预测确认，不能直接据此发放置动作。
        if (target.item() instanceof BlockItem blockItem
                && blockItem.getBlock() == target.block()
                && acceptsEveryState(target)) {
            return true;
        }

        // 立式／墙式共用的物品另作预检放宽：没指定精确属性，且墙上朝向与点击面一致，也先保留。
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

    // 遍历这一方块注册的全部可能状态；全都可接受才说明此目标不挑摆放姿态。这里每次都会遍历，没有单独缓存。
    private static boolean acceptsEveryState(BuildTaskRecord.Target target) {
        for (BlockState possible : target.block().getStateDefinition().getPossibleStates()) {
            if (!target.acceptsPlacedState(possible)) return false;
        }
        return true;
    }

    // 把待比较状态里的整数属性临时换成目标值，再比较其他摆放属性；只是构造比较用状态，不改世界。
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
        if (target.finalProperties() != null) {
            if (desired.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE)
                adjusted = adjusted.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
            return target.acceptsPlacedState(adjusted);
        }
        return BuildValidity.sameBlockState(adjusted, desired);
    }

    static List<BlockPos> candidateStances(BlockPos target) {
        return STANCE_OFFSETS.stream().map(target::offset).toList();
    }

    // 预先生成四种相对高度、水平一到四格的方形边界站位，按离目标近远排序；没有正好在目标同一列的站位。
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
        // 每个小碰撞盒各取面上的点；后续用整体外形射线过滤藏在方块内部或被别的部分挡住的面。
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

    // 在某个碰撞盒的指定面上按比例取点，再向面内缩一点，避免射线终点恰好落在表面造成误差。
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
        // 内缩量为万分之一格，减少终点落在表面时的浮点误差。
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

    // 调用方块自己的放置状态计算，但把视角与蹲下状态换成候选点击的值，不实际转头或发动作。
    // 落点仍交给原版 BlockPlaceContext 决定；若物品会放到别的格子，不能强行把它解释成目标格。
    private static NativePlacement predictPlacement(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                                     float yaw, float pitch, boolean sneak, BlockPos target) {
        return predictPlacement(player, stack, hit, yaw, pitch, sneak, target, false);
    }

    private static NativePlacement predictPlacement(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                                     float yaw, float pitch, boolean sneak, BlockPos target,
                                                     boolean projectedSupport) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        Direction[] nearest = NativePlacementDirections.ordered(yaw, pitch);
        try {
            return PlacementSneakProjection.withCandidate(player, sneak, () -> {
                BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                        player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}) {
                    // Only the hypothetical full support's destination differs; execution still uses the real context.
                    @Override public BlockPos getClickedPos() {
                        return projectedSupport ? hit.getBlockPos().relative(hit.getDirection()) : super.getClickedPos();
                    }
                    @Override public boolean replacingClickedOnBlock() {
                        return !projectedSupport && super.replacingClickedOnBlock();
                    }
                    @Override public boolean isSecondaryUseActive() { return sneak; }
                    @Override public Direction getHorizontalDirection() {
                        return Direction.fromYRot(yaw);
                    }
                    @Override public float getRotation() { return yaw; }
                    @Override public Direction getNearestLookingDirection() {
                        return nearest[0];
                    }
                    @Override public Direction getNearestLookingVerticalDirection() {
                        return NativePlacementDirections.vertical(pitch);
                    }
                    @Override public Direction[] getNearestLookingDirections() {
                        return NativePlacementDirections.forPlacement(nearest, getClickedFace(), replacingClickedOnBlock());
                    }
                };
                // 例如点同类半砖可能补成点击格的双层砖，而不是放到邻格；必须连落点也匹配本次目标。
                BlockPos destination = context.getClickedPos();
                return new NativePlacement(destination, destination.equals(target)
                        ? org.maiwithu.maicraft.core.integration.machine.MachinePlacementItems.projectedFinalState(stack,
                            player.level(), destination, context.getHorizontalDirection(), blockItem.getBlock().getStateForPlacement(context)) : null);
            });
        } catch (RuntimeException ignored) {
            return null;
        }
    }

}
