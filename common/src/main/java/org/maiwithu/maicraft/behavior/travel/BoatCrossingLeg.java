// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 划一段船：备船（身上有船换到主手，岸边停着的船直接走过去用）→ 走到岸边能放船的站位 →
 * 对着水面放船、确认附近真的出现一条新船 → 右键坐上去、确认坐的正是它 → 按住前进、左右转向朝靠岸点划，
 * 每刻看前方避障（顶到岸、搁浅、睡莲、冰先倒退再转向，连着几个观察窗没前进换靠岸点）→
 * 贴岸按住潜行下船、确认人不在船上、脚下站得住 → 船留在岸边不收，位置记进世界记忆，站稳交还行程。
 *
 * <p>路上饿了、被打照常让生存需求打断：松开按键、船停在原地，回来接着划。
 * 没划到（船碎了、被人拉下船、连着倒退也转不过去）如实说停在哪、为什么，行程从落点重新规划。
 */
final class BoatCrossingLeg implements TravelLeg {

    /** 划一段的几步。 */
    enum Step { PREP, WALK, PLACE, MOUNT, ROW, DISMOUNT, SETTLE }

    /** 放船、上船等服务器确认的期限。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 30;
    /** 按住潜行这么久还没下船就放弃。 */
    private static final long DISMOUNT_LIMIT_TICKS = 40;
    /** 下了船这么多刻还站不到地上（也没站得住的地方）就按落水收场。 */
    private static final int DISMOUNT_SETTLE_LIMIT_TICKS = 60;
    /** 离靠岸点这么近（格）就开始贴岸下船。 */
    private static final double ARRIVAL_DISTANCE = 2.0;
    /** 上岸后脚下这么多刻没动算站稳。 */
    private static final int STILL_TICKS = 10;

    private final BoatCrossing.Plan plan;
    private final Permissions permissions;
    private final DimensionTravelServices services;

    private Step step = Step.PREP;
    private Action current;
    private WalkRun walk;
    private BlockPos walkTarget;
    private List<BlockPos> walkStances = List.of();
    private UUID boatId;
    private int landingIndex;
    private Set<UUID> boatsBefore = Set.of();
    private int boatItemsBefore;
    private boolean boatPlaced;
    private PendingInteraction pending;
    private long lastTick = Long.MIN_VALUE;
    private long dismountStarted = Long.MIN_VALUE;
    private int afterDismountTicks;
    private BlockPos windowAnchor;
    private long windowStart;
    private int stalledWindows;
    private int reverseTicksLeft;
    private boolean stopRequested;
    private BlockPos lastFeet;
    private int still;
    private BlockPos leftTheBoatAt;
    private final List<Change> changes = new ArrayList<>();

    BoatCrossingLeg(BoatCrossing.Plan plan, Permissions permissions, DimensionTravelServices services) {
        this.plan = plan;
        this.permissions = permissions;
        this.services = services;
    }

    /** 这一段朝哪划、在哪上岸：靠岸点挨个换，全换完还没到就停在这里如实说。 */
    private BlockPos landing() {
        return plan.landings().get(Math.min(landingIndex, plan.landings().size() - 1));
    }

    private Function<BlockPos, Optional<BlockState>> reader() {
        return services.world()::stateAt;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext live = services.context().get();
        if (live == null || live.localPlayer() == null) {
            return failed("角色不在世界里，划不了船", Problem.Kind.INTERNAL_ERROR, null);
        }
        if (lastTick == live.clientTick()) return ActionStatus.running();
        lastTick = live.clientTick();
        if (live.input().humanTookOver()) {
            releaseKeys(live);
            return failed("划船途中人接回了角色，这一段停下", Problem.Kind.STUCK, null);
        }
        if (pending != null) {
            ActionStatus settled = settlePending(live);
            if (settled != null) return settled;
        }
        if (current != null) return runCurrent(context, live);
        return switch (step) {
            case PREP -> prep(live);
            case PLACE -> place(live);
            case MOUNT -> mount(live);
            case ROW -> row(live);
            case DISMOUNT -> dismount(live);
            case SETTLE -> settleDown(live);
            case WALK -> resumeWalk();
        };
    }

    // 推进手上的动作（换主手、走到）；做完按步骤接下一步，走不到如实失败。
    private ActionStatus runCurrent(TickContext context, PlayerContext live) {
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) {
            // 走到被停下（让给生存需求）：收掉这一趟，回来从原地重新走。
            if (current == walk && walk.report().state() == WalkReport.State.STOPPED) dropCurrent();
            return status;
        }
        dropCurrent();
        Problem problem = status instanceof ActionStatus.Failed failed ? failed.problem() : null;
        if (step == Step.PREP) return afterPrep(problem, live);
        if (step == Step.WALK) return afterWalk(problem, live);
        return ActionStatus.running();
    }

    // 备船：已经坐在原版船里（上一段没划完接着划）直接去划；岸边停着的船走过去坐；身上有船换到主手去放。
    private ActionStatus prep(PlayerContext live) {
        if (live.localPlayer().getVehicle() instanceof Boat riding) {
            boatId = riding.getUUID();
            return startRowing();
        }
        if (plan.existingBoat() != null) {
            return walkTo(plan.existingBoat(), neighborsOf(plan.existingBoat()));
        }
        if (plan.boatItem() != null) {
            Optional<Action> move = services.toMainhand().moveToMainhand(plan.boatItem());
            if (move.isEmpty()) {
                return walkTo(plan.launchFeet(), List.of(plan.launchFeet()));
            }
            current = move.get();
            return ActionStatus.progressed();
        }
        return failed("身上没有船物品，岸边也没有停着的空船，划不了这一段", Problem.Kind.UNSUPPORTED, null);
    }

    // 换好主手：走到放船的岸边站位。
    private ActionStatus afterPrep(Problem problem, PlayerContext live) {
        if (problem != null) {
            return failed("船物品换不到主手：" + problem.message(), problem.kind(), problem.suggestion());
        }
        return walkTo(plan.launchFeet(), List.of(plan.launchFeet()));
    }

    // 走到岸边站位（或停着的船旁）：记下要去哪，走到被让路收掉后从原地照原目标重走。
    private ActionStatus walkTo(BlockPos target, List<BlockPos> stances) {
        step = Step.WALK;
        walkTarget = target.immutable();
        walkStances = List.copyOf(stances);
        walk = services.walks().start(GoalCompiler.interactionStances(target, stances),
                TerrainPermit.of(permissions));
        current = walk;
        return ActionStatus.progressed();
    }

    // 走到被让路（饿、被打）后回来：已停下的走到不会自己接着走，从原地照原目标重新上路。
    private ActionStatus resumeWalk() {
        return current == null ? walkTo(walkTarget, walkStances) : ActionStatus.running();
    }

    private ActionStatus afterWalk(Problem problem, PlayerContext live) {
        if (problem != null) {
            return failed("走不到岸边" + (plan.existingBoat() != null ? "停着的船旁" : "能放船的站位")
                    + "：" + problem.message(), problem.kind(), problem.suggestion());
        }
        return plan.existingBoat() != null ? mount(live) : place(live);
    }

    // 放船：对准勘测好的那格水面，准星真落在水面上才出手；附近恰好新出现一条普通船才算放成了。
    private ActionStatus place(PlayerContext live) {
        step = Step.PLACE;
        var player = live.localPlayer();
        Vec3 aim = Vec3.atCenterOf(plan.launchWater());
        look(live, aim, false);
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(aim) > player.blockInteractionRange()) return ActionStatus.running();
        // 准星得正落在那格水面上：没对准下一刻接着转，不出手。
        var hit = live.level().clip(new ClipContext(eye, eye.add(player.getViewVector(1)
                .scale(player.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(plan.launchWater())) {
            return ActionStatus.running();
        }
        if (!live.canInteractThisTick()) return ActionStatus.running();
        boatsBefore = new HashSet<>();
        for (Boat boat : live.level().getEntitiesOfClass(Boat.class, new AABB(plan.launchWater()).inflate(2))) {
            boatsBefore.add(boat.getUUID());
        }
        boatItemsBefore = countBoatItems(live);
        pending = live.interactionSender().useItem(live, InteractionHand.MAIN_HAND,
                InteractionConfirmation.serverObservedEntity(this::observePlacement), CONFIRM_TIMEOUT_TICKS);
        return ActionStatus.progressed();
    }

    // 放下去的船：放的位置附近恰好新出现一条普通船就是它；同时冒出两条说不清是哪条，按对不上收场。
    private InteractionConfirmation.Verdict observePlacement(PlayerContext live) {
        List<UUID> fresh = new ArrayList<>();
        for (Boat boat : live.level().getEntitiesOfClass(Boat.class, new AABB(plan.launchWater()).inflate(0.5))) {
            if (!boatsBefore.contains(boat.getUUID())) fresh.add(boat.getUUID());
        }
        if (fresh.size() != 1) {
            return fresh.size() > 1 ? InteractionConfirmation.Verdict.DIVERGED : InteractionConfirmation.Verdict.PENDING;
        }
        boatId = fresh.getFirst();
        boatPlaced |= countBoatItems(live) < boatItemsBefore;
        return InteractionConfirmation.Verdict.APPLIED;
    }

    // 上船：船还在、没人坐才右键它；以真坐在这条船里为准。用岸边现成的船时先按记下的位置认出它是哪条。
    private ActionStatus mount(PlayerContext live) {
        step = Step.MOUNT;
        var player = live.localPlayer();
        Boat boat = findBoat(live);
        if (boat == null && boatId == null && plan.existingBoat() != null) {
            for (Boat nearby : live.level().getEntitiesOfClass(Boat.class,
                    new AABB(plan.existingBoat()).inflate(3))) {
                if (nearby.isAlive() && nearby.getPassengers().isEmpty()) {
                    boatId = nearby.getUUID();
                    boat = nearby;
                    break;
                }
            }
        }
        if (boat == null) {
            return failed("要坐的那条船不见了", Problem.Kind.UNREACHABLE, null);
        }
        if (!boat.getPassengers().isEmpty()) {
            return failed("那条船被人坐了，这一段坐不成", Problem.Kind.UNREACHABLE, null);
        }
        look(live, boat.getBoundingBox().getCenter(), false);
        if (!player.canInteractWithEntity(boat, 0) || player.isShiftKeyDown() || !live.canInteractThisTick()) {
            return ActionStatus.running();
        }
        UUID target = boatId;
        pending = live.interactionSender().interact(live, boat, InteractionHand.MAIN_HAND,
                InteractionConfirmation.serverObservedEntity(l -> target.equals(vehicleId(l))
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING),
                CONFIRM_TIMEOUT_TICKS);
        return ActionStatus.progressed();
    }

    // 坐稳开划：从当前位置朝靠岸点划，每刻看前方避障、每个观察窗看前进没前进。
    private ActionStatus startRowing() {
        step = Step.ROW;
        PlayerContext live = services.context().get();
        windowAnchor = live == null ? null : live.localPlayer().blockPosition();
        windowStart = lastTick;
        return ActionStatus.progressed();
    }

    // 划：船没了、人不在船里都如实失败；靠岸点到了就贴岸下船。
    private ActionStatus row(PlayerContext live) {
        var player = live.localPlayer();
        Boat boat = findBoat(live);
        if (boat == null) {
            releaseKeys(live);
            return failed("船没了（被打碎或漂走了），人停在 " + player.blockPosition().toShortString(),
                    Problem.Kind.UNREACHABLE, null);
        }
        if (!(player.getVehicle() instanceof Boat riding) || !riding.getUUID().equals(boatId)) {
            releaseKeys(live);
            return failed("半路不在船上了（被人或生物拉下船），船停在 " + boat.blockPosition().toShortString(),
                    Problem.Kind.UNREACHABLE, null);
        }
        // 被要求停下：松开按键如实说停在哪；人还在船里，行程下一轮会由"非它不可"接着送回岸上。
        if (stopRequested) {
            releaseKeys(live);
            return failed("按要求停下：船停在水面 " + boat.blockPosition().toShortString() + "，人还在船上",
                    Problem.Kind.STUCK, null);
        }
        Vec3 boatPos = boat.position();
        Vec3 target = Vec3.atCenterOf(landing());
        if (Math.sqrt(boatPos.distanceToSqr(target.x, boatPos.y, target.z)) <= ARRIVAL_DISTANCE) {
            releaseKeys(live);
            dismountStarted = live.clientTick();
            step = Step.DISMOUNT;
            return ActionStatus.progressed();
        }
        float error = BoatSteering.yawError(boat.getYRot(), boatPos, target);
        boolean blocked = BoatSteering.blockedAhead(reader(), boatPos, boat.getYRot());
        // 观察窗结算：这一窗没挪出四分之三格算没前进，连着几个窗没前进就换靠岸点。
        if (windowAnchor != null && live.clientTick() - windowStart >= BoatSteering.WINDOW_TICKS) {
            double moved = Math.sqrt(boatPos.distanceToSqr(windowAnchor.getX() + 0.5, windowAnchor.getY(),
                    windowAnchor.getZ() + 0.5));
            stalledWindows = moved >= 0.75 ? 0 : stalledWindows + 1;
            windowAnchor = boat.blockPosition();
            windowStart = live.clientTick();
        }
        BoatSteering.Decision decision = BoatSteering.decide(error, blocked,
                new BoatSteering.Pace(stalledWindows, reverseTicksLeft));
        if (decision.switchLanding()) {
            if (landingIndex >= plan.landings().size() - 1) {
                releaseKeys(live);
                return failed("连着 " + BoatSteering.MAX_STALLED_WINDOWS + " 个观察窗没前进，靠岸点都换过了，停在水面 "
                        + boat.blockPosition().toShortString(), Problem.Kind.UNREACHABLE, null);
            }
            landingIndex++;
            stalledWindows = 0;
            reverseTicksLeft = 0;
            windowAnchor = boat.blockPosition();
            windowStart = live.clientTick();
            return ActionStatus.progressed();
        }
        if (blocked && reverseTicksLeft == 0) reverseTicksLeft = BoatSteering.REVERSE_TICKS;
        if (reverseTicksLeft > 0) reverseTicksLeft--;
        look(live, target, false);
        live.input().applyMovement(new PlayerInput.Movement(decision.forward(), decision.strafe(),
                false, false, false), live.clientTick());
        return ActionStatus.running();
    }

    // 下船：贴岸按住潜行（按键在这里给），下来了等站稳、脚下有支撑才算上岸；船留在水里不收。
    private ActionStatus dismount(PlayerContext live) {
        var player = live.localPlayer();
        if (player.isPassenger()) {
            live.input().applyMovement(new PlayerInput.Movement(0, 0, false, true, false), live.clientTick());
            if (live.clientTick() - dismountStarted > DISMOUNT_LIMIT_TICKS) {
                return failed("按住潜行两秒还没下船，船停在 " + player.blockPosition().toShortString() + " 附近",
                        Problem.Kind.STUCK, null);
            }
            return ActionStatus.running();
        }
        if (!player.onGround()) return ActionStatus.running();
        // 脚下站得住：那一柱地表是实地（不是水、不是悬空）。
        if (WaterCrossing.standFeet(reader(), player.blockPosition().getX(), player.blockPosition().getZ(),
                player.blockPosition().getY()).isEmpty()) {
            return failed("下了船但脚下没有支撑，站在 " + player.blockPosition().toShortString(),
                    Problem.Kind.UNREACHABLE, null);
        }
        // 船留在岸边不收：位置记下来，下次从这一侧回去先用它。
        Boat boat = findBoat(live);
        leftTheBoatAt = boat != null ? boat.blockPosition() : player.blockPosition();
        changes.add(Change.of(Change.Kind.OTHER, "船留在 " + leftTheBoatAt.toShortString() + "，没有收回来", 1));
        services.memory().remember(BoatCrossing.LEFT_BOAT_PLACE, new WorldPosition(leftTheBoatAt.getX(),
                leftTheBoatAt.getY(), leftTheBoatAt.getZ(), services.world().currentDimension()));
        step = Step.SETTLE;
        still = 0;
        lastFeet = player.blockPosition();
        return ActionStatus.progressed();
    }

    // 上岸站稳：脚下半秒没动这段就算完成，交还行程从岸上接着走。
    private ActionStatus settleDown(PlayerContext live) {
        BlockPos feet = live.localPlayer().blockPosition();
        still = feet.equals(lastFeet) ? still + 1 : 0;
        lastFeet = feet;
        return still >= STILL_TICKS ? ActionStatus.done() : ActionStatus.running();
    }

    // 等放船、上船的确认：确认过了接下一步，没确认如实失败，不放第二条、不右键别的东西。回了非空表示这段有了结论。
    private ActionStatus settlePending(PlayerContext live) {
        pending = live.interactionSender().poll(live, pending);
        if (!pending.terminal()) return null;
        Problem problem = pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED ? null
                : Problem.of(Problem.Kind.UNREACHABLE, pending.detail());
        pending = null;
        if (problem != null) {
            String doing = step == Step.PLACE ? "放船" : "上船";
            return failed(doing + "没得到服务器确认：" + problem.message(), problem.kind(), null);
        }
        if (step == Step.PLACE) {
            if (boatId == null) {
                return failed("放了船但没认出哪条是新放的，不冒认", Problem.Kind.UNREACHABLE, null);
            }
            if (boatPlaced) {
                changes.add(Change.of(Change.Kind.ITEM_CONSUMED,
                        plan.boatItem() + "（放到水里 " + plan.launchWater().toShortString() + " 附近）", 1));
            }
            step = Step.MOUNT;
        } else if (step == Step.MOUNT) {
            return startRowing();
        }
        return null;
    }

    /** 人此刻坐的载具编号；不在任何东西上给 null。 */
    private static UUID vehicleId(PlayerContext live) {
        return live.localPlayer().getVehicle() == null ? null : live.localPlayer().getVehicle().getUUID();
    }

    private Boat findBoat(PlayerContext live) {
        if (boatId == null) return null;
        for (Boat boat : live.level().getEntitiesOfClass(Boat.class,
                live.localPlayer().getBoundingBox().inflate(32))) {
            if (boatId.equals(boat.getUUID())) return boat;
        }
        return null;
    }

    /** 身上的船物品一共几件：与报价用同一份身上清单，两条路径认的是同一批东西。 */
    private int countBoatItems(PlayerContext ignored) {
        int count = 0;
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (BoatCrossing.isBoatItem(stack.itemId())) count += stack.count();
        }
        return count;
    }

    /** 站到船旁的落脚格：四邻加上船所在那格，走得到哪个算哪个。 */
    private static List<BlockPos> neighborsOf(BlockPos center) {
        return List.of(center.north(), center.south(), center.west(), center.east(), center);
    }

    // 转头看向一点：平滑地转，不打断身体。
    private static void look(PlayerContext live, Vec3 point, boolean urgent) {
        var player = live.localPlayer();
        Vec3 delta = point.subtract(player.getEyePosition());
        double flat = Math.hypot(delta.x, delta.z);
        float yaw = flat < .001 ? player.getYRot() : (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, flat));
        if (urgent) live.input().requestImmediateLook(yaw, pitch, live.clientTick());
        else live.input().requestLook(yaw, pitch, live.clientTick());
    }

    // 松开身体：船上的前进与平移都停，船自己滑行减速。
    private void releaseKeys(PlayerContext live) {
        live.input().applyMovement(PlayerInput.Movement.STOPPED, live.clientTick());
    }

    private ActionStatus failed(String message, Problem.Kind kind, String suggestion) {
        releaseKeysIfPossible();
        return ActionStatus.failed(Problem.of(kind, message, suggestion));
    }

    private void releaseKeysIfPossible() {
        PlayerContext live = services.context().get();
        if (live != null && live.localPlayer() != null) releaseKeys(live);
    }

    private void dropCurrent() {
        if (current != null) current.close();
        current = null;
        walk = null;
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    // 要求先到安全的地方再停：划在半路停不下来，等这一段把人送上岸或如实停下。
    @Override public void requestSafeStop() {
        stopRequested = true;
    }

    // 让给生存需求：松开按键船停原地，回来接着划；走到那一趟收掉，回来从原地重走。
    @Override public void pause() {
        releaseKeysIfPossible();
        dropCurrent();
    }

    @Override public void close() {
        releaseKeysIfPossible();
        dropCurrent();
    }

    @Override public List<Change> changes() {
        return List.copyOf(changes);
    }

    @Override public String summary() {
        StringBuilder text = new StringBuilder("划船：");
        if (leftTheBoatAt != null) {
            text.append("上了岸，船留在 ").append(leftTheBoatAt.toShortString());
        } else {
            text.append("还没划到岸");
            PlayerContext live = services.context().get();
            if (live != null && live.localPlayer() != null) {
                text.append("，人在 ").append(live.localPlayer().blockPosition().toShortString());
            }
        }
        return text.toString();
    }

    @Override public String describe() {
        return switch (step) {
            case PREP -> "备船（换主手或去找停着的船）";
            case WALK -> "走到岸边" + (plan.existingBoat() != null ? "停着的船旁" : "能放船的站位");
            case PLACE -> "对着水面放船";
            case MOUNT -> "坐上船";
            case ROW -> "朝靠岸点划（当前靠岸点 " + landing().toShortString() + "）";
            case DISMOUNT -> "贴岸下船";
            case SETTLE -> "上岸站稳";
        };
    }
}
