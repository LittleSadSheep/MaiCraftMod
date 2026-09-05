package org.maiwithu.maicraft.core.pathing.execute;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.astar.NavPath;
import org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementAscend;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementDescend;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementDiagonal;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementFall;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementParkour;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementTraverse;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * 疾跑整体决策:按当前移动类型与前后文判定这一 tick 该不该疾跑,以及
 * 随之而来的跳步与按键(直跳上台、V 形谷冲刺、连锁下降、坠落前越)。
 *
 * <p><b>不改路径与世界。</b>此前这套启发住在执行器里一个名叫
 * {@code shouldSprintNextTick} 的"谓词"里,却在方法体内改 pathPosition、
 * 递归重入 onTick、按跳跃键——那正是"递归超路长即取消"守卫存在的根因。
 * 现在所有副作用折成 {@link Decision} 的显式字段,由执行器在<b>一处</b>
 * 统一施加;这里只读世界、只算结论。策略仅保留一个 travel-jump 是否仍在
 * 空中的物理阶段位,以便越过方块 Y 边界后继续持有前进,落地即由事实清除。
 * 另一个既有例外是
 * {@link MovementDescend#forceSafeMode}——那是下降原语自己的安全档位,
 * 属于移动的属性而非执行器的状态。
 */
final class SprintPolicy {

    /** 超出这个物理飞行窗仍不落地的属性组合不交给普通赶路跳。 */
    private static final int MAX_PROJECTED_AIRBORNE_TICKS = 40;
    /** 原版普通地面的摩擦系数;更滑的支撑不采用一格制动余量。 */
    private static final float NORMAL_GROUND_FRICTION = 0.6F;

    /**
     * 一次疾跑裁决。
     *
     * @param sprint  这一 tick 要不要疾跑
     * @param skipTo  跳步:直接把路径下标推进到此处并重跑一遍推进管线
     *                (-1 = 不跳)。直跳上台/V 形谷/连锁下降/坠落吸附都走它
     * @param jumpUp  跳步之后按下跳跃(直跳上台的起跳)
     * @param jumpDown 松开跳跃(V 形谷底动量直冲,不再起跳)
     * @param steer   需要强制维持前进的压舵目标:清键后直接压视线与前进(null = 无)
     */
    record Decision(boolean sprint, int skipTo, boolean jumpUp, boolean jumpDown, Vec3 steer) {
        static final Decision NO = new Decision(false, -1, false, false, null);
        static final Decision YES = new Decision(true, -1, false, false, null);
    }

    private final NavPath path;
    private final LocalPlayer player;
    /** 执行期重算成本用的上下文(霜行者判定要它)。 */
    private final Supplier<CalculationContext> contextSupplier;
    /** 本策略发起的普通赶路跳仍在空中;落地或路线事实失效时立即清除。 */
    private boolean travelJumpActive;
    /** 起跳时的既定直线方向;空中不接受后续 movement 把身体拐出该走廊。 */
    private Vec3i travelJumpDirection;

    SprintPolicy(NavPath path, LocalPlayer player, Supplier<CalculationContext> contextSupplier) {
        this.path = path;
        this.player = player;
        this.contextSupplier = contextSupplier;
    }

    /**
     * A path splice changes only the immutable route representation, not the physical jump that
     * is already in flight. Carry that one local episode into the replacement policy; its next
     * {@link #decide(int, boolean)} call still validates the live movement/direction and clears
     * the episode immediately on landing or any route/world mismatch.
     */
    void inheritTravelJumpEpisode(SprintPolicy previous) {
        if (previous == null || !previous.travelJumpActive
                || previous.travelJumpDirection == null) {
            travelJumpActive = false;
            travelJumpDirection = null;
            return;
        }
        travelJumpActive = true;
        Vec3i direction = previous.travelJumpDirection;
        travelJumpDirection = new Vec3i(
                direction.getX(), direction.getY(), direction.getZ());
    }

    /**
     * 裁决这一 tick 的疾跑。{@code requested} 是移动原语被没收前请求的
     * SPRINT 键(没收动作在执行器,这里只收结论)。
     */
    Decision decide(int pathPosition, boolean requested) {
        // 与成本模型同判据:允许疾跑且饥饿值足够
        if (!(NavSettings.get().allowSprint
                && (!WorkProfile.of(player).hasHunger()
                        || player.getFoodData().getFoodLevel() > 6))) {
            travelJumpActive = false;
            travelJumpDirection = null;
            return Decision.NO;
        }
        Movement current = path.movements().get(pathPosition);

        if (travelJumpActive) {
            if (player.onGround()) {
                travelJumpActive = false;
                travelJumpDirection = null;
            } else {
                Vec3 travelSteer = travelJumpSteer(current);
                if (travelSteer != null) {
                    // MovementTraverse intentionally waits when the feet block rises during a
                    // jump. Keep the one-tick body lease driving forward until physics reports
                    // the landing; otherwise a "sprint jump" degenerates into a short coast.
                    return new Decision(true, -1, false, false, travelSteer);
                }
                travelJumpActive = false;
                travelJumpDirection = null;
            }
        }

        // 平走→上台直跳:跳过平走那步,原地起跳直接冲上去
        if (player.onGround() && !player.isInWater() && !player.isPassenger()
                && !player.hasEffect(MobEffects.LEVITATION)
                && !player.hasEffect(MobEffects.SLOW_FALLING)
                && current instanceof MovementTraverse traverse
                && pathPosition < path.length() - 3) {
            Movement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend ascend
                    && sprintableAscend(traverse, ascend, path.movements().get(pathPosition + 2))
                    && skipNow(current)
                    && ascendLaunchReady(
                            traverse, ascend, path.movements().get(pathPosition + 2))) {
                Constants.LOG.debug("跳过平走,直跳上台");
                return new Decision(true, pathPosition + 1, true, false, null);
            }
        }

        if (requested) {
            // A long, already-planned flat run is travelled like a player would travel it:
            // sprint-jump while there is enough runway to land back on the same safe route.
            // This is deliberately an execution optimisation, not a second path search.  In
            // particular, a convenient two-block-high tree tunnel is exploited only when the
            // chosen path already passes through it; the route is never bent merely to find a
            // head-hitter.  The flat-run lookahead also leaves the final cells (or an upcoming
            // rise/drop/turn) to the ordinary precise movement policy below.
            if (shouldTravelJump(pathPosition, current)) {
                travelJumpActive = true;
                travelJumpDirection = current.getDirection();
                return new Decision(true, -1, true, false, null);
            }
            return Decision.YES;
        }

        // 下降与上升不自行请求疾跑(它们不知道后面接什么),在此按前后文补
        if (current instanceof MovementDescend descend) {
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                CalculationContext context = contextSupplier.get();
                if (MovementHelper.canUseFrostWalker(context, context.get(next.getDest().below()))) {
                    // 霜行者只在贴地跨过方块边缘时结冰,可能冲过头;下一步
                    // 同向平走/跑酷时强制慢速直进(跑酷且有耗材可放置替代除外)
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = context.hasThrowaway && next instanceof MovementParkour;
                        boolean sameFlatDirection =
                                !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO);
                        if (sameFlatDirection && !couldPlaceInstead) {
                            descend.forceSafeMode();
                        }
                    }
                }
            }
            if (descend.safeMode() && !descend.skipToAscend()) {
                return Decision.NO; // 冲下去不安全
            }
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend
                        && current.getDirection().above().equals(next.getDirection().below())) {
                    // V 形:同向下降接上升,直接跳到上升步冲过去
                    Constants.LOG.debug("V 形谷,跳过下降直接上升");
                    return new Decision(true, pathPosition + 1, false, false, null);
                }
                if (canSprintFromDescendInto(current, next)) {
                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        Movement nextNext = path.movements().get(pathPosition + 2);
                        if (nextNext instanceof MovementDescend
                                && !canSprintFromDescendInto(next, nextNext)) {
                            return Decision.NO; // 连锁下降的下一环接不上,别开冲
                        }
                    }
                    if (PathExecutor.playerFeet(player).equals(current.getDest())) {
                        return new Decision(true, pathPosition + 1, false, false, null);
                    }
                    return Decision.YES;
                }
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            Movement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend
                    && prev.getDirection().above().equals(current.getDirection().below())) {
                // V 形谷底:动量还在,高度够了就松跳直接冲上去
                BlockPos center = current.getSrc().above();
                // 0.07 的余量吸收农田/灵魂沙顶面的矮一截
                if (player.position().y >= center.getY() - 0.07) {
                    return new Decision(true, -1, false, true, null);
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse traverse
                    && sprintableAscend(traverse, (MovementAscend) current,
                            path.movements().get(pathPosition + 1))) {
                return Decision.YES;
            }
        }
        if (current instanceof MovementFall fall) {
            Vec3 overrideTarget = overrideFallTarget(fall, pathPosition);
            if (overrideTarget != null) {
                BlockPos fallDest = overrideFallDest(fall, pathPosition);
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException("坠落前越落点 " + fallDest + " 不在路径上");
                }
                if (PathExecutor.playerFeet(player).equals(fallDest)) {
                    return new Decision(true, path.positions().indexOf(fallDest), false, false, null);
                }
                // 疾跑冲下坡不减速:清键、直接压目标视线与前进(施加在执行器)
                return new Decision(true, -1, false, false, overrideTarget);
            }
        }
        return Decision.NO;
    }

    /**
     * Whether to start the next sprint jump on a route that is already being executed.
     *
     * <p>A normal sprint jump needs several straight, dry cells before the next structural
     * movement.  A two-block-high corridor produces the familiar short head-hit hops and
     * therefore needs less runway.  Both cases are derived from the concrete path cells: no
     * waypoint is invented, and an imminent one-block rise is left to the dedicated
     * traverse-to-ascend launch above so its take-off remains correctly timed.
     */
    private boolean shouldTravelJump(int pathPosition, Movement current) {
        if (!player.onGround() || player.isInWater() || player.isPassenger()
                || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)) {
            return false;
        }
        var level = LoadedOnlyView.of(player.level());
        if (!(current instanceof MovementTraverse)
                || !isSafeDryTraverse(current, level)) {
            return false;
        }

        Vec3i direction = current.getDirection();
        if (direction.getY() != 0
                || Math.abs(direction.getX()) + Math.abs(direction.getZ()) != 1) {
            return false;
        }

        // A jump is only useful once forward travel has actually established momentum.  This
        // is a physical predicate rather than a timer: a newly-started route first accelerates
        // under ordinary sprint control, then becomes eligible on the first grounded tick whose
        // velocity points down the chosen edge.
        Vec3 velocity = player.getDeltaMovement();
        double forwardSpeed = velocity.x * direction.getX() + velocity.z * direction.getZ();
        if (forwardSpeed <= 0.0) {
            return false;
        }

        BlockPos feet = PathExecutor.playerFeet(player);
        boolean headHit = feet.equals(current.getSrc())
                && hasFullTwoBlockCeiling(level, current.getSrc())
                && hasFullTwoBlockCeiling(level, current.getDest());
        JumpProjection projection = projectJump(direction, headHit);
        if (projection == null) {
            return false;
        }

        // The projection is already bounded by MAX_PROJECTED_AIRBORNE_TICKS. Scan only the
        // concrete cells that can contain that flight plus the worst structural reserve (two
        // cells for a following ascent). Once that finite runway is proven, the rest of a long
        // straight path cannot change this launch decision and is deliberately not inspected.
        double worstControlReserve = 2.0 + player.getBbWidth() * 0.5 + forwardSpeed;
        double sufficientRunway = projection.forwardDistance() + worstControlReserve;
        int lastSafeMovement = pathPosition - 1;
        BlockPos runEnd = current.getSrc();
        boolean boundedRunwayProven = false;
        for (int i = pathPosition; i < path.movements().size(); i++) {
            Movement movement = path.movements().get(i);
            if (!direction.equals(movement.getDirection())
                    || !(movement instanceof MovementTraverse)
                    || !isSafeDryTraverse(movement, level)) {
                break;
            }
            lastSafeMovement = i;
            runEnd = movement.getDest();
            double checkedRunway = direction.getX()
                    * (runEnd.getX() + 0.5 - player.getX())
                    + direction.getZ() * (runEnd.getZ() + 0.5 - player.getZ());
            if (checkedRunway > sufficientRunway) {
                boundedRunwayProven = true;
                break;
            }
        }
        if (lastSafeMovement < pathPosition) {
            return false;
        }

        double remainingRunway = direction.getX() * (runEnd.getX() + 0.5 - player.getX())
                + direction.getZ() * (runEnd.getZ() + 0.5 - player.getZ());
        Movement afterRun = !boundedRunwayProven
                && lastSafeMovement + 1 < path.movements().size()
                ? path.movements().get(lastSafeMovement + 1)
                : null;
        // One complete planned cell is retained for grounded steering/braking.  An ascent needs
        // a second cell because its dedicated traverse->ascend jump launches from the source of
        // the final flat edge; the travel hop must have landed before entering that launch cell.
        double controlReserve = boundedRunwayProven
                ? worstControlReserve
                : (afterRun instanceof MovementAscend ? 2.0 : 1.0)
                        + player.getBbWidth() * 0.5 + forwardSpeed;
        double maximumLandingDistance = remainingRunway - controlReserve;
        if (maximumLandingDistance <= 0.0) {
            return false;
        }

        // Keep the whole body in the one-block-wide lane while airborne.  The current lateral
        // velocity is allowed only when vanilla air drag will dissipate it before the AABB can
        // leave that already-validated route column.
        double lateralOffset = Math.abs(direction.getX()
                * (current.getSrc().getZ() + 0.5 - player.getZ()))
                + Math.abs(direction.getZ()
                * (current.getSrc().getX() + 0.5 - player.getX()));
        Vec3 launchVelocity = sprintJumpLaunchVelocity();
        double lateralSpeed = Math.abs(direction.getX() * launchVelocity.z
                + direction.getZ() * launchVelocity.x);
        double lateralRoom = 0.5 - player.getBbWidth() * 0.5 - lateralOffset;
        if (lateralRoom <= 0.0
                || lateralSpeed * projection.airDragSum() >= lateralRoom) {
            return false;
        }

        if (projection.forwardDistance() >= maximumLandingDistance) {
            return false;
        }

        // An open-air jump may not discover a canopy halfway through its flight.  Conversely a
        // head-hit hop is enabled only after the body is already under a contiguous run of full
        // collision ceilings long enough to cover this exact projected hop.  Path positions are
        // checked once each, so a shared movement endpoint cannot fake a two-cell corridor.
        return ceilingProfileMatches(pathPosition, lastSafeMovement, direction,
                projection.forwardDistance(), headHit, level);
    }

    /** Maintain the airborne half of a travel jump only while its current edge remains valid. */
    private Vec3 travelJumpSteer(Movement current) {
        if (player.isInWater() || player.isPassenger()
                || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)
                || !(current instanceof MovementTraverse)
                || !isSafeDryTraverse(current, LoadedOnlyView.of(player.level()))) {
            return null;
        }
        Vec3i direction = current.getDirection();
        if (direction.getY() != 0
                || Math.abs(direction.getX()) + Math.abs(direction.getZ()) != 1
                || !direction.equals(travelJumpDirection)) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        return new Vec3(player.getX() + direction.getX(),
                eye.y,
                player.getZ() + direction.getZ());
    }

    /** A cardinal flat edge with full-height dry support and no edits/body-squeeze. */
    private static boolean isSafeDryTraverse(Movement movement, BlockGetter level) {
        if (!(movement instanceof MovementTraverse)
                || movement.getSrc().getY() != movement.getDest().getY()) {
            return false;
        }
        if (!movement.toBreak(level).isEmpty()
                || !movement.toPlace(level).isEmpty()
                || !movement.toWalkInto(level).isEmpty()) {
            return false;
        }
        return isSafeDryCell(level, movement.getSrc())
                && isSafeDryCell(level, movement.getDest());
    }

    private static boolean isSafeDryCell(BlockGetter level, BlockPos feet) {
        BlockPos support = feet.below();
        var supportState = level.getBlockState(support);
        return supportState.getFluidState().isEmpty()
                && supportState.isCollisionShapeFullBlock(level, support)
                && supportState.getBlock().getFriction() <= NORMAL_GROUND_FRICTION
                && MovementHelper.fullyPassable(level, feet)
                && MovementHelper.fullyPassable(level, feet.above());
    }

    /** True only for a full collision ceiling exactly two blocks above this route cell. */
    private static boolean hasFullTwoBlockCeiling(BlockGetter level, BlockPos feet) {
        BlockPos ceiling = feet.above(2);
        var state = level.getBlockState(ceiling);
        return state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(level, ceiling);
    }

    private boolean ceilingProfileMatches(
            int pathPosition, int lastSafeMovement, Vec3i direction,
            double forwardDistance, boolean headHit, BlockGetter level) {
        double bodyHalf = player.getBbWidth() * 0.5;
        double sweptUntil = forwardDistance + bodyHalf;
        BlockPos first = path.movements().get(pathPosition).getSrc();
        if (!ceilingCellMatches(level, first, headHit)) {
            return false;
        }
        for (int i = pathPosition; i <= lastSafeMovement; i++) {
            BlockPos cell = path.movements().get(i).getDest();
            double ahead = direction.getX() * (cell.getX() + 0.5 - player.getX())
                    + direction.getZ() * (cell.getZ() + 0.5 - player.getZ());
            if (ahead - bodyHalf > sweptUntil) {
                return true;
            }
            if (!ceilingCellMatches(level, cell, headHit)) {
                return false;
            }
            if (ahead >= sweptUntil) {
                return true;
            }
        }
        return false;
    }

    private static boolean ceilingCellMatches(
            BlockGetter level, BlockPos feet, boolean requireFullCeiling) {
        if (requireFullCeiling) {
            return hasFullTwoBlockCeiling(level, feet);
        }
        BlockPos ceiling = feet.above(2);
        var state = level.getBlockState(ceiling);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(level, ceiling).isEmpty();
    }

    /**
     * Conservative vanilla jump projection from the live route-aligned velocity.
     * Vertical flight time is integrated from the player's authoritative jump/gravity
     * attributes; horizontal distance includes the sprint-jump impulse and held-forward air
     * control. A full ceiling clamps the first upward collision and naturally shortens the hop.
     */
    private JumpProjection projectJump(Vec3i direction, boolean headHit) {
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        double verticalSpeed = jumpVerticalSpeed();
        if (gravity <= 0.0 || verticalSpeed <= 0.0) {
            return null;
        }
        double height = 0.0;
        double headroom = Math.max(0.0, 2.0 - player.getBbHeight());
        Vec3 launchVelocity = sprintJumpLaunchVelocity();
        double forwardSpeed = Math.max(0.0,
                launchVelocity.x * direction.getX()
                        + launchVelocity.z * direction.getZ());
        double forwardDistance = 0.0;
        double airDragSum = 0.0;
        double drag = 1.0;
        int airborneTicks = 0;
        do {
            // The local physical flight window keeps extremely small modded gravity from turning
            // one client tick into an unbounded numerical loop. The caller converts this bounded
            // projection into the exact finite number of route movements it needs to inspect.
            if (airborneTicks >= MAX_PROJECTED_AIRBORNE_TICKS) {
                return null;
            }
            forwardDistance += forwardSpeed;
            airDragSum += drag;
            forwardSpeed = (forwardSpeed + 0.02) * 0.91;
            drag *= 0.91;

            double nextHeight = height + verticalSpeed;
