package org.maiwithu.maicraft.core.integration.create.elevator;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorBridge.Cabin;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorGeometry.Landing;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorMotion.Progress;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorSurvey.Plan;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.entity.InputDriver;

/**
 * 执行一次乘梯：找到同时有入口、控制位置和出口的方案，呼梯、进厢、选层、随梯移动，再走到固定地面。
 * 取消后先处理退出和遥控器归位；没有可靠出口时明确报告仍需处理，不能把还在轿厢里说成已下梯。
 */
public final class CreateElevatorTravel implements TransportSession {
    private enum Phase { DISCOVER, APPROACH_CALL, CALL, WAIT, APPROACH_BOARD, BOARD, WALK_CONTROL, SELECT, RIDE, WALK_EXIT, EXIT }
    private final BlockPos destination;
    private final UUID requestedCabin;
    private final Integer requestedFloor;
    private final LongSet forbidden;
    private final CreateElevatorBridge bridge = ElevatorInspection.bridge();
    private final ElevatorActions actions = new ElevatorActions();
    private final ElevatorMotion motion = new ElevatorMotion();
    private final Set<UUID> requestedLists = new HashSet<>();
    private Map<String, Object> surveyEvidence = Map.of();
    private Phase phase = Phase.DISCOVER;
    private Plan plan, best;
    private Landing exit;
    private int scanned, groundTicks, geometryHash;
    private ElevatorGeometry geometry;
    private long epoch = -1, now, lastProgress;
    private Vec3 previousPosition;
    private Vec3 departure;
    private UUID observedCabin;
    private double previousCabinY = Double.NaN;
    private boolean stopRequested, aboard, safe, effects;
    private boolean stoppingExit;
    private int exitFloor;
    private int exitSearchFloor = Integer.MIN_VALUE, observationFailures;
    private long exitSearchStarted, nextExitSearch;
    private long exitBlockedSince = -1;
    private boolean needsAttention;
    private String pendingFailure;
    private Result terminal;
    private BodyControlPort ownedBody;

    public CreateElevatorTravel(BlockPos destination) { this(destination, LongSets.emptySet()); }
    public CreateElevatorTravel(BlockPos destination, LongSet forbiddenBodyCells) {
        this.destination = destination.immutable();
        requestedCabin=null; requestedFloor=null;
        forbidden = LongSets.unmodifiable(new LongOpenHashSet(forbiddenBodyCells));
    }
    public CreateElevatorTravel(UUID cabin,int floor,LongSet forbiddenBodyCells) {
        destination=null; requestedCabin=java.util.Objects.requireNonNull(cabin); requestedFloor=floor;
        forbidden=LongSets.unmodifiable(new LongOpenHashSet(forbiddenBodyCells));
    }
    public static Map<String, Object> inspect(LocalPlayer player) { return ElevatorInspection.inspect(player); }
    public static Map<String, Object> probe(LocalPlayerContext context, BlockPos destination) { return ElevatorInspection.probe(context, destination); }

    // 这里传入的 hasChunkAt 在原版客户端不能确认区块已加载；相应未知区域判断目前不能依赖它。
    @Override public Result tick(LocalPlayerContext ctx) {
        if (terminal != null) return terminal;
        if (bridge == null) return terminal = Result.failed("create_elevator_unavailable", "Create elevator client API is unavailable", false, false);
        ctx.requireCurrent(); ownedBody = ctx.body(); now = ctx.level().getGameTime();
        if (epoch < 0) {
            epoch = ctx.bodyEpoch(); lastProgress = now; previousPosition = ctx.player().position();
            departure = ElevatorGeometry.staticStance(ctx.level(), ctx.level()::hasChunkAt, previousPosition,
                    ctx.player().getBbWidth(), ctx.player().getBbHeight(), 0.08);
            if (departure == null) {
                var supported = bridge.cabins(ctx).stream().filter(c -> ElevatorInspection.supports(c, ctx.player()) || bridge.recentSupport(c, ctx.player())).findFirst();
                if (supported.isEmpty()) return terminal = Result.failed("unsupported_elevator_departure", "start on a fixed landing or a supported cabin floor", false, false);
                observedCabin = supported.orElseThrow().entity().getUUID(); aboard = true;
            }
        }
        if (ctx.bodyEpoch() != epoch || !ctx.permitsNativeActions()) { abandon(); return terminal; }
        ElevatorMotion.stop(ctx);
        try {
            if (needsAttention) return actions.settle(ctx) ? finish(ctx, false) : running();
            UUID activeCabin = plan == null ? observedCabin : plan.cabin();
            Cabin cabin = activeCabin == null ? null : bridge.find(ctx, activeCabin);
            if (cabin != null) {
                int hash = geometryHash(cabin.blocks());
                if (geometry == null || hash != geometryHash || geometry.width != ctx.player().getBbWidth() || geometry.height != ctx.player().getBbHeight()) {
                    geometry = new ElevatorGeometry(cabin.blocks(), cabin.view(), ctx.player().getBbWidth(), ctx.player().getBbHeight());
                    geometryHash = hash;
                }
                aboard = geometry.carries(cabin.local(ctx.player().position())) || bridge.recentSupport(cabin, ctx.player());
                if (!Double.isNaN(previousCabinY) && Math.abs(cabin.entity().getY() - previousCabinY) > 0.0001) lastProgress = now;
                previousCabinY = cabin.entity().getY();
            }
            Vec3 position = ctx.player().position();
            if (previousPosition.distanceToSqr(position) > 0.0001) { effects = true; lastProgress = now; previousPosition = position; }
            boolean staticGround = ctx.player().onGround() && ElevatorGeometry.staticStance(ctx.level(), ctx.level()::hasChunkAt,
                    position, ctx.player().getBbWidth(), ctx.player().getBbHeight(), 0.08) != null;
            if (cabin == null && staticGround) aboard = false;
            groundTicks = staticGround && !aboard ? groundTicks + 1 : 0;
            safe = groundTicks >= 3;
            if (!allowsCurrentScreen(ctx)) return running();
            boolean pending = actions.pending();
            if (!actions.settle(ctx)) return running();
            effects |= actions.inventoryChanged();
            if (pending) lastProgress = now;
            if (actions.failure != null) { pendingFailure = actions.failure; actions.failure = null; stopRequested = true; }
            if (actions.remoteHeld && plan != null && plan.call() != null) {
                actions.releaseRemote(ctx, bridge, plan.call()); return running();
            }
            if (activeCabin != null && cabin == null) {
                aboard = false; actions.uncertain = true; stopRequested = true;
                pendingFailure = "the selected moving support disappeared before safe disembarkation";
                if (departure == null && plan == null) return finish(ctx, false);
            }
            if (stopRequested) return stopSafely(ctx, cabin);
            if (plan != null && cabin == null) { pendingFailure = "the selected cabin is no longer synchronized"; stopRequested = true; return stopSafely(ctx, null); }
            if (now - lastProgress > 200 && !motion.planning()) {
                pendingFailure = "elevator made no physical or confirmed protocol progress during " + phase;
                stopRequested = true; return stopSafely(ctx, cabin);
            }
            return advance(ctx, cabin);
        } catch (RuntimeException failure) {
            pendingFailure = "elevator integration failed: " + failure.getMessage(); stopRequested = true;
            if (++observationFailures >= 3) {
                needsAttention = true; actions.uncertain = true;
                motion.abandon(); actions.abandon(); ownedBody.releaseAll();
                return terminal = Result.failed("elevator_observation_failed", pendingFailure + "; remaining aboard or inventory effects require inspection", effects, true);
            }
            if (safe && !actions.pending() && !actions.remoteHeld && motion.releaseNavigation()) return finish(ctx, false);
            return running();
        }
    }

    // 每个阶段只推进当前动作；呼梯已生效或正在来这层时先等，不再重复按按钮。
    private Result advance(LocalPlayerContext ctx, Cabin cabin) {
        switch (phase) {
            case DISCOVER -> {
                var cabins = bridge.cabins(ctx).stream().filter(c -> observedCabin == null || observedCabin.equals(c.entity().getUUID()))
                        .filter(c->requestedCabin==null || requestedCabin.equals(c.entity().getUUID()))
                        .sorted(Comparator.comparingInt(c -> c.entity().getId())).toList();
                if (scanned >= cabins.size()) {
                    if (best == null) return terminal = Result.failed("no_proven_elevator_route",
                            "no jointly verified elevator route; rejected candidates: " + surveyEvidence.getOrDefault("rejected_candidates", "no loaded cabin"), effects, false);
                    plan = best; exit = plan.exit();
                    exitFloor = plan.toFloor();
                    setPhase(plan.aboard() ? plan.control() == null ? Phase.WALK_EXIT : Phase.WALK_CONTROL : Phase.APPROACH_CALL); return running();
                }
                Cabin candidate = cabins.get(scanned);
                if (candidate.floors().isEmpty() && !requestedLists.contains(candidate.entity().getUUID())) {
                    UUID id = candidate.entity().getUUID();
                    if (actions.submit(ctx, "create:elevator_floor_list", () -> bridge.requestFloors(candidate), c -> {
                        Cabin live = bridge.find(c, id);
                        return live != null && !live.floors().isEmpty() ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
                    })) requestedLists.add(id);
                    return running();
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                Plan found = ElevatorSurvey.find(ctx, destination, forbidden, bridge, candidate, evidence,requestedFloor);
                surveyEvidence = Map.copyOf(evidence);
                if (found != null && (best == null || found.score() < best.score())) best = found;
                scanned++; lastProgress = now;
            }
            case APPROACH_CALL -> {
                if (cabin.aligned(plan.fromFloor()) || cabin.targetY() == plan.fromFloor()) { setPhase(Phase.WAIT); break; }
                if (plan.call() == null) return fail(ctx, "no associated native call input is available");
                Progress p = motion.approach(ctx, plan.call().stance(), forbidden);
                if (p == Progress.BLOCKED) return fail(ctx, motion.failure);
                if (p == Progress.REACHED && motion.releaseNavigation()) setPhase(Phase.CALL);
            }
            case CALL -> {
                if (actions.call(ctx, bridge, cabin, plan.call(), plan.fromFloor())) { effects = true; setPhase(Phase.WAIT); }
            }
            case WAIT -> {
                if (cabin.aligned(plan.fromFloor())) setPhase(Phase.APPROACH_BOARD);
                else if (cabin.targetY() != plan.fromFloor()) return fail(ctx, "the cabin was called to a different floor");
            }
            case APPROACH_BOARD -> {
                if (!cabin.aligned(plan.fromFloor())) return fail(ctx, "the cabin left before boarding");
                Progress p = motion.approach(ctx, plan.board().outside(), forbidden);
                if (p == Progress.BLOCKED) return fail(ctx, motion.failure);
                if (p == Progress.REACHED && motion.releaseNavigation()) {
                    p = motion.step(ctx, cabin, geometry, plan.board().outside(), forbidden);
                    if (p == Progress.REACHED) setPhase(Phase.BOARD);
                }
            }
            case BOARD -> {
                if (!cabin.aligned(plan.fromFloor())) return fail(ctx, "the cabin left while boarding");
                Progress p = motion.step(ctx, cabin, geometry, cabin.global(plan.board().inside()), forbidden);
                if (p == Progress.REACHED && aboard) setPhase(plan.control() == null ? Phase.WALK_EXIT : Phase.WALK_CONTROL);
            }
            case WALK_CONTROL -> {
                Progress p = motion.inside(ctx, cabin, geometry, plan.controlStance(), forbidden);
                if (p == Progress.REACHED) setPhase(Phase.SELECT);
            }
            case SELECT -> {
                InputDriver.lookAt(ctx.player(), bridge.controlAim(cabin, plan.control()));
                if (bridge.hit(ctx, cabin, plan.control()) == null) break;
                UUID id = plan.cabin(); BlockPos control = plan.control(); int target = plan.toFloor();
                int selected = bridge.selected(cabin, control);
                if (selected == Integer.MIN_VALUE) break;
                if (selected != target) {
                    if (!bridge.scrollHit(ctx, cabin, control)) break;
                    actions.submit(ctx, "create:elevator_select_floor", () -> bridge.scroll(ctx, cabin, control, target), c -> {
                        Cabin live = bridge.find(c, id);
                        return live != null && bridge.selected(live, control) == target ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
                    });
                } else if (cabin.targetY() == target) setPhase(Phase.RIDE);
                else if (actions.submit(ctx, "create:elevator_confirm_floor", () -> bridge.click(ctx, cabin, control, target), c -> {
                    Cabin live = bridge.find(c, id);
                    return live != null && live.targetY() == target ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
                })) { effects = true; setPhase(Phase.RIDE); }
            }
            case RIDE -> {
                if (!aboard) return fail(ctx, "the body lost its supported cabin floor before arrival");
                if (cabin.targetY() != plan.toFloor()) return fail(ctx, "another input changed the cabin destination");
                if (cabin.aligned(plan.toFloor())) setPhase(Phase.WALK_EXIT);
            }
            case WALK_EXIT, EXIT -> { return exit(ctx, cabin, false); }
        }
        return running();
    }

    private Result exit(LocalPlayerContext ctx, Cabin cabin, boolean cancelled) {
        if (safe) {
            if (!cancelled && Math.abs(ctx.player().getY() - exit.outside().y) > 0.13) {
                pendingFailure = "the body reached a different fixed layer than the requested exit";
                return finish(ctx, false);
            }
            return finish(ctx, !cancelled);
        }
        if (!cancelled && cabin.targetY() != plan.toFloor()) return fail(ctx, "destination changed during disembarkation");
        if (!cabin.aligned(exitFloor)) { exitBlockedSince = -1; return running(); }
        Progress progress;
        if (phase != Phase.EXIT) {
            progress = motion.inside(ctx, cabin, geometry, exit.inside(), forbidden);
            if (progress == Progress.REACHED) setPhase(Phase.EXIT);
        } else progress = motion.step(ctx, cabin, geometry, exit.outside(), forbidden);
        if (cancelled && progress == Progress.BLOCKED) {
            if (exitBlockedSince < 0) exitBlockedSince = now;
            if (now - exitBlockedSince >= 20) {
                pendingFailure = "cancelled exit remains blocked; body remains aboard and needs attention";
                needsAttention = true; actions.uncertain = true;
                return finish(ctx, false);
            }
        } else exitBlockedSince = -1;
        return running();
    }

    // 人在厢外时尝试回可靠地面；人在厢内时等轿厢停稳，再找当前位置真正开着的出口。
    private Result stopSafely(LocalPlayerContext ctx, Cabin cabin) {
        Vec3 recoveryGoal = departure != null ? departure : plan == null ? null : plan.exit().outside();
        if (!aboard && !safe && recoveryGoal != null) {
            Progress recovery = motion.approach(ctx, recoveryGoal, forbidden);
            if (recovery == Progress.BLOCKED && motion.releaseNavigation()) {
                pendingFailure = "could not return to the verified departure landing: " + motion.failure;
                actions.uncertain = true;
                return finish(ctx, false);
            }
            return running();
        }
        if (!motion.releaseNavigation()) return running();
        if (safe) return finish(ctx, false);
        if (cabin == null || !aboard || !cabin.aligned(cabin.targetY())) return running();
        if (!stoppingExit || exitFloor != cabin.targetY()) {
            if (exitSearchFloor != cabin.targetY()) {
                exitSearchFloor = cabin.targetY(); exitSearchStarted = now; nextExitSearch = now;
            }
            if (now < nextExitSearch) return running();
            nextExitSearch = now + 5;
            exit = geometry.landings(ctx.level(), ctx.level()::hasChunkAt, cabin.origin(), ctx.player().maxUpStep(), forbidden).stream()
                    .filter(l -> geometry.canStep(ctx.level(), ctx.level()::hasChunkAt, cabin.origin(), cabin.global(l.inside()),
                            l.outside(), ctx.player().maxUpStep(), forbidden))
                    .min(Comparator.comparingDouble(l -> l.outside().distanceToSqr(ctx.player().position()))).orElse(null);
            if (exit == null) {
                if (now - exitSearchStarted < 20) return running();
                pendingFailure = "docked cabin has no verified open exit; body remains aboard and needs attention";
                needsAttention = true; actions.uncertain = true;
                return finish(ctx, false);
            }
            stoppingExit = true; exitFloor = cabin.targetY();
            setPhase(Phase.WALK_EXIT);
        }
        return exit(ctx, cabin, true);
    }

    private Result fail(LocalPlayerContext ctx, String detail) { pendingFailure = detail; stopRequested = true; return running(); }
    private Result finish(LocalPlayerContext ctx, boolean success) {
        if (!actions.cleanup(ctx)) return running();
        effects |= actions.inventoryChanged();
        motion.abandon();
        terminal = success ? new Result(State.SUCCEEDED, "elevator_landing_reached", "body is supported on the fixed destination landing", effects, actions.uncertain)
                : Result.failed(needsAttention ? "elevator_needs_attention" : "elevator_stopped", pendingFailure == null ? "cancelled after reaching a safe fixed landing" : pendingFailure, effects, actions.uncertain);
        return terminal;
    }
    private Result running() { return new Result(State.RUNNING, phase(), pendingFailure == null ? "" : pendingFailure, effects, actions.uncertain); }
    private void setPhase(Phase next) { phase = next; motion.resetLocalPath(); lastProgress = now; }
    // 只把方块位置和状态用于判断是否重建碰撞，楼层展示文字等附加数据不应让走路路线反复失效。
    static int geometryHash(Map<BlockPos, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> blocks) {
        int hash = blocks.size();
        for (var entry : blocks.entrySet()) hash += entry.getKey().hashCode() ^ System.identityHashCode(entry.getValue().state());
        return hash;
    }
    @Override public void requestStop() { stopRequested = true; }
    @Override public void abandon() {
        effects |= actions.inventoryChanged();
        motion.abandon(); actions.abandon(); if (ownedBody != null) ownedBody.releaseAll();
        terminal = Result.failed("elevator_abandoned", "manual/body handoff; no further elevator packets were sent", effects, effects || actions.remoteHeld);
    }
    @Override public boolean safeToInterrupt() { return safe && !actions.pending() && !actions.remoteHeld && !actions.ownsPreparation(); }
    @Override public boolean allowsCurrentScreen(LocalPlayerContext context) {
        return DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen) || actions.ownsInventory(context);
    }
    @Override public boolean livenessActive() { return terminal == null && (actions.pending() || actions.pendingInventory() || motion.active() || now - lastProgress < 100); }
    @Override public String phase() { return "elevator_" + phase.name().toLowerCase(java.util.Locale.ROOT); }
    @Override public Map<String, Object> diagnostics() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase()); data.put("aboard", aboard); data.put("safe_to_interrupt", safeToInterrupt());
        data.put("stop_requested", stopRequested); data.put("effects_started", effects); data.put("remote_input_held", actions.remoteHeld);
        data.put("needs_attention", needsAttention);
        data.put("survey", surveyEvidence);
        data.putAll(motion.diagnostics());
        if (plan != null && plan.board() != null) data.put("boarding", Map.of(
                "outside_world", ElevatorInspection.point(plan.board().outside()),
                "inside_local", ElevatorInspection.point(plan.board().inside())));
        if (plan != null) data.put("planned_exit", Map.of("outside_world", ElevatorInspection.point(plan.exit().outside()),
                "inside_local", ElevatorInspection.point(plan.exit().inside()),
                "control_stance_local", ElevatorInspection.point(plan.controlStance())));
        if (plan != null) { data.put("cabin_uuid", plan.cabin().toString()); data.put("from_contact_y", plan.fromFloor()); data.put("to_contact_y", plan.toFloor()); }
        if (pendingFailure != null) data.put("detail", pendingFailure);
        return Map.copyOf(data);
    }
}
