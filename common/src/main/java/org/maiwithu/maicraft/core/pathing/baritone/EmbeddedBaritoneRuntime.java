// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.InputOverrideHandler;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.entity.InputDriver;

/**
 * 全局只保留一个实际操纵玩家的 Baritone 实例。每次由当前任务提出驱动要求，再在角色更新末尾统一推进导航和输入。
 * 新导航要等旧导航能安全交接；这里也负责世界切换、保护范围更新、行走视角以及游泳／落地时的输入衔接。
 */
public final class EmbeddedBaritoneRuntime {
    private static long lastSwimDriveTick = Long.MIN_VALUE;
    private static final EmbeddedBaritoneActionBridge ACTIONS =
            new EmbeddedBaritoneActionBridge();
    private static final EmbeddedBuildScaffoldSelection BUILD_SCAFFOLDS = new EmbeddedBuildScaffoldSelection();
    private static IBaritone backend;
    private static EmbeddedBaritoneNavigator owner;
    private static ClientLevel world;
    private static LocalPlayerContext tickingContext;
    private static EmbeddedBaritoneNavigator pendingPolicyOwner;
    private static GoalCompiler.Compiled pendingPolicyGoal;
    private static PendingStart pendingStart;
    private static volatile org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot physicalObstacles =
            org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot.EMPTY;
    private static long physicalObservationTick = Long.MIN_VALUE;
    private static net.minecraft.world.phys.Vec3 physicalObservationOrigin;
    private static org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot sableObstacles=
            org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot.EMPTY;

    public static org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot physicalObstacles() {
        return physicalObstacles;
    }

    // 大型物理结构观察每五刻或移动四格后刷新一次；Create 移动结构每次重新合入，供路线避障使用。
    private static void refreshPhysicalObstacles(LocalPlayer player) {
        long tick = player.level().getGameTime();
        if (physicalObservationOrigin == null || tick < physicalObservationTick
                || tick - physicalObservationTick >= 5 || physicalObservationOrigin.distanceToSqr(player.position()) >= 16) {
            sableObstacles = org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot.capture(player.clientLevel,player.position());
            physicalObservationTick=tick; physicalObservationOrigin=player.position();
        }
        physicalObstacles=sableObstacles.plus(org.maiwithu.maicraft.core.integration.create.ContraptionObstacles.capture(player.clientLevel,player.position()));
    }

    private record PendingStart(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            TerrainPermit permit,
            boolean sprintAllowed,
            EmbeddedBaritonePolicy.Snapshot policy) {
        private PendingStart(EmbeddedBaritoneNavigator navigator, GoalCompiler.Compiled compiled,
                             TerrainPermit permit, boolean sprintAllowed) {
            this(navigator, compiled, permit, sprintAllowed,
                    EmbeddedBaritonePolicy.capture(compiled.sacred(),
                            navigator.protectedMutationCells(), navigator.forbiddenBodyCells(), navigator.minimumFeetY()));
        }
    }

    private EmbeddedBaritoneRuntime() {}

    /** Optional live diagnostics, without bootstrapping an otherwise idle Baritone instance. */
    public static java.util.Map<String, Object> diagnosticState() {
        requireClientThread();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("has_owner", owner != null);
        result.put("pending_owner", pendingStart != null);
        result.put("last_drive_tick", lastSwimDriveTick);
        result.put("physical_obstacles", java.util.Map.of("state", physicalObstacles.state(),
                "boxes", physicalObstacles.boxes().size(), "block_reads", physicalObstacles.blockReads(),
                "conservative_structures", physicalObstacles.conservativeStructures(), "game_time", physicalObservationTick));
        if (backend == null) return result;
        var pathing = (PathingBehavior) backend.getPathingBehavior();
        result.put("planning", pathing.getInProgress().isPresent());
        result.put("safe_to_cancel", pathing.isSafeToCancel());
        var executor = pathing.getCurrent();
        if (executor == null) return result;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        result.put("path_index", index);
        result.put("path_length", movements.size());
        if (index >= 0 && index < movements.size()) {
            var movement = movements.get(index);
            result.put("movement", movement.getClass().getSimpleName());
            result.put("from", movement.getSrc().toShortString());
            result.put("to", movement.getDest().toShortString());
        }
        result.put("input", backend.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD));
        return result;
    }

    /** The developer overlay observes only the selected executor, never the search worker. */
    public static org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot debugPath() {
        requireClientThread();
        if (owner == null || backend == null || world != Minecraft.getInstance().level) return null;
        var executor = ((PathingBehavior) backend.getPathingBehavior()).getCurrent();
        if (executor == null) return null;
        var positions = executor.getPath().positions();
        if (positions.isEmpty()) return null;
        int cursor = Math.clamp(executor.getPosition(), 0, positions.size() - 1);
        int start = Math.max(0, cursor - 1);
        var points = positions.subList(start, Math.min(positions.size(), start + 512)).stream()
                .map(pos -> net.minecraft.world.phys.Vec3.atBottomCenterOf(pos).add(0, 0.08, 0)).toList();
        var destination = net.minecraft.world.phys.Vec3.atBottomCenterOf(positions.getLast()).add(0, 0.08, 0);
        var next = net.minecraft.world.phys.Vec3.atBottomCenterOf(
                positions.get(Math.min(cursor + 1, positions.size() - 1))).add(0, 0.08, 0);
        return new org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot(
                points, cursor - start, destination, next);
    }

    static void startOrUpdate(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            TerrainPermit permit,
            boolean sprintAllowed) {
        requireClientThread();
        IBaritone baritone = backend();
        // 已有别的导航占用身体时，把新请求排为待接手，并让旧导航先到达安全停止位置。
        if (owner != null && owner != navigator) {
            EmbeddedBaritoneNavigator previous = owner;
            queuePendingStart(new PendingStart(navigator, compiled, permit, sprintAllowed));
            previous.preemptWhenSafe(
                    "another MaiCraft navigation requested the first-person body");
            if (owner == null) promotePendingStart(baritone);
            return;
        }

        if (owner == null) {
            queuePendingStart(new PendingStart(navigator, compiled, permit, sprintAllowed));
            promotePendingStart(baritone);
            return;
        }

        configure(baritone, permit, sprintAllowed);
        EmbeddedBaritonePolicy.install(
                compiled.sacred(),
                navigator.protectedMutationCells(),
                navigator.forbiddenBodyCells(), navigator.minimumFeetY());
        PathingBehavior pathing = (PathingBehavior) baritone.getPathingBehavior();
        if (!pathing.isSafeToCancel()) {
            pendingPolicyOwner = navigator;
            pendingPolicyGoal = compiled;
            return;
        }
        replaceActiveRoute(navigator, compiled, currentContext(),
                "the navigation goal contract was refreshed");
    }

    static boolean refreshPolicy(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled) {
        if (owner != navigator) return false;
        boolean changed = EmbeddedBaritonePolicy.install(
                compiled.sacred(),
                navigator.protectedMutationCells(),
                navigator.forbiddenBodyCells(), navigator.minimumFeetY());
        if (!changed || backend == null) return changed;
        PathingBehavior pathing = (PathingBehavior) backend.getPathingBehavior();
        if (!pathing.isSafeToCancel()) {
            pendingPolicyOwner = navigator;
            pendingPolicyGoal = compiled;
            return true;
        }
        replaceActiveRoute(navigator, compiled, currentContext(),
                "navigation safety policy changed while a route was active");
        return true;
    }

    /**
     * Submit one read-only terrain-changing second opinion for the current owner. The policy is
     * frozen with the failed preserve calculation; the returned path is diagnostic evidence and
     * is never installed into the live path executor.
     */
    static EmbeddedBaritoneTerrainProbe.ProbeFuture submitTerrainProbe(
            EmbeddedBaritoneNavigator navigator,
            BlockPos start,
            org.maiwithu.maicraft.core.pathing.calc.NavGoal goal) {
        requireClientThread();
        if (owner != navigator || backend == null) return null;
        return EmbeddedBaritoneTerrainProbe.submit(
                backend, start, goal, EmbeddedBaritonePolicy.snapshot());
    }

    static EmbeddedBaritonePolicy.Snapshot policySnapshot(
            EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        return owner == navigator ? EmbeddedBaritonePolicy.snapshot() : null;
    }

    /** Called exactly once at the end of an open MaiCraft actor tick. */
    public static void tick(LocalPlayerContext context, boolean schedulerAllowsBodyWork) {
        requireClientThread();
        lastDrivenOwner = null;
        IBaritone baritone = backend;
        if (baritone == null) return;
        syncWorld(baritone, context.level());
        promotePendingStart(baritone);

        EmbeddedBaritoneNavigator current = owner;
        if (current == null) {
            // The embedded backend has no claim on this tick's body. A semantic task may have
            // released navigation and immediately issued its own close-range movement (loot
            // pickup, portal approach, boat hand-off, and similar). Halting here would make the
            // dormant backend the last writer and erase that legitimate command.
            baritone.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) baritone.getLookBehavior()).clearTarget();
            ACTIONS.suspendAny(context,
                    "the navigation owner released its first-person body");
            return;
        }
        // A task may have dropped its last PlayerNav reference after requesting a terminal
        // outcome. Settle it here if the previous tick reached a safe movement boundary; while
        // still airborne, keep only the already-selected movement alive even if normal scheduler
        // work has moved on. Clearing its steering would turn cleanup into an avoidable fall.
        current.settlePendingFailureAtSafeBoundary();
        if (owner != current) {
            promotePendingStart(baritone);
            return;
        }
        boolean requested = current.consumeDriveRequest();
        // 普通推进需要本次调度允许且导航提出驱动请求；已经失去调用者但仍要安全落地的旧导航，可继续必要收尾。
        boolean drive = current.requiresOrphanContinuation()
                || (schedulerAllowsBodyWork && requested);
        if (!drive) {
            BUILD_SCAFFOLDS.cancel(current);
            lastSwimDriveTick = Long.MIN_VALUE;
            baritone.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) baritone.getLookBehavior()).clearTarget();
            ACTIONS.suspend(context, current,
                    "navigation did not retain physical drive this tick");
            return;
        }

        if (pendingPolicyOwner == current
                && pendingPolicyGoal != null
                && ((PathingBehavior) baritone.getPathingBehavior()).isSafeToCancel()) {
            replaceActiveRoute(current, pendingPolicyGoal, context,
                    "a deferred navigation contract became safe to apply");
            if (!context.mutationAvailable()) {
                baritone.getInputOverrideHandler().clearAllKeys();
                ((LookBehavior) baritone.getLookBehavior()).clearTarget();
                return;
            }
        }

        try {
            if (prepareBuildScaffold(context, current)) {
                baritone.getInputOverrideHandler().clearAllKeys();
                InputDriver.halt(context.player());
                return;
            }
            refreshPhysicalObstacles(context.player());
            lastSwimDriveTick = context.level().getGameTime();
            tickingContext = context;
            BiFunction<EventState, TickEvent.Type, TickEvent> events =
                    TickEvent.createNextProvider();
            baritone.getGameEventHandler().onTick(
                    events.apply(EventState.PRE, TickEvent.Type.IN));
            baritone.getGameEventHandler().onPostTick(
                    events.apply(EventState.POST, TickEvent.Type.IN));
            lastDrivenOwner = current;
            lastDrivenRevision = context.tickRevision();
        } catch (RuntimeException failure) {
            Constants.LOG.error("[maicraft-path] embedded Baritone tick failed", failure);
            current.internalFailure("embedded pathing tick failed: "
                    + failure.getClass().getSimpleName());
            release(current);
        } finally {
            tickingContext = null;
        }
        if (owner == current) current.settlePendingFailureAtSafeBoundary();
        if (owner != current) promotePendingStart(baritone);
    }

    static void suspend(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null) return;
        BUILD_SCAFFOLDS.cancel(navigator);
        lastSwimDriveTick = Long.MIN_VALUE;
        backend.getInputOverrideHandler().clearAllKeys();
        ((LookBehavior) backend.getLookBehavior()).clearTarget();
        ACTIONS.suspend(currentContext(), navigator, "navigation suspended");
    }

    /**
     * Yield only physical outputs to a higher-priority first-person winner while retaining the
     * calculated route. This never writes the body itself; the winning behavior owns that tick.
     */
    public static void suspendActivePhysicalOutputs() {
        requireClientThread();
        lastSwimDriveTick = Long.MIN_VALUE;
        if (backend == null || owner == null) return;
        backend.getInputOverrideHandler().clearAllKeys();
        ((LookBehavior) backend.getLookBehavior()).clearTarget();
        ACTIONS.suspend(currentContext(), owner,
                "navigation yielded to another first-person winner");
    }

    /**
     * Whether the active route is at a physical hand-off point. A fall or launched parkour
     * movement must retain its steering until it reaches a cancellable state; clearing its keys
     * first and asking afterwards turns a scheduler preemption into an avoidable fall.
     */
    public static boolean canSafelySuspendActive() {
        requireClientThread();
        return backend == null || owner == null
                || ((PathingBehavior) backend.getPathingBehavior()).isSafeToCancel();
    }

    /** A stopped route may still own a landing and its item-selection transaction. */
    public static boolean yieldActiveForExternalAction(LocalPlayer player) {
        requireClientThread();
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (owner != null) owner.settlePendingFailureAtSafeBoundary();
        if (owner != null && owner.requiresOrphanContinuation()) return false;
        return owner == null ? context.mutationAvailable() : owner.yieldForExternalAction();
    }

    /** A normal jump keeps its trajectory until its feet have actually missed the target support. */
    public static boolean canHandOffMissedLanding(LocalPlayer player) {
        requireClientThread();
        if (player == null || owner == null || backend == null || world != player.clientLevel
                || backend.getPlayerContext().player() != player || player.onGround()
                || player.getDeltaMovement().y >= 0
                || org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.occupied()) return false;
        var executor = backend.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        int cursor = executor.getPosition();
        if (cursor < 0 || cursor >= movements.size() || movements.get(cursor) instanceof MovementFall) return false;
        var support = movements.get(cursor).getDest().below();
        if (!world.isLoaded(support)) return false;
        double height = baritone.pathing.movement.CollisionGeometry.supportHeight(world, support);
        return Double.isFinite(height) && height > 0 && player.getBoundingBox().minY < support.getY() + height - 0.01;
    }

    /** Called only after MLG has resolved a legal continuation, before it writes this tick's inputs. */
    // 确认已经错过原落脚面后，解除旧路线的控制，把后续救援交给专门落地逻辑。
    public static boolean handOffMissedLanding(LocalPlayer player) {
        if (!canHandOffMissedLanding(player)) return false;
        var previous = owner;
        previous.detachForLandingRescue();
        release(previous);
        return true;
    }

    static void release(EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        if (owner != navigator) return;
        BUILD_SCAFFOLDS.cancel(navigator);
        if (backend != null) {
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
            ACTIONS.suspend(currentContext(), navigator, "navigation ended");
            ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
        }
        EmbeddedBaritonePolicy.clear();
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        CAMERA_COURSE.reset();
        owner = null;
    }

    public static void bodyGone() {
        requireClientThread();
        lastDrivenOwner = null;
        if (owner != null) owner.preempted("the local-player body or world disappeared");
        if (pendingStart != null) {
            pendingStart.navigator().preempted(
                    "the local-player body or world disappeared before body acquisition");
        }
        if (backend != null) {
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
            ACTIONS.suspend(currentContext(), owner,
                    "the local-player body or world disappeared");
            ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
            backend.getGameEventHandler().onWorldEvent(
                    new WorldEvent(null, EventState.POST));
        }
        ACTIONS.bodyGone();
        BUILD_SCAFFOLDS.reset();
        owner = null;
        world = null;
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        pendingStart = null;
        EmbeddedBaritonePolicy.clear();
        CAMERA_COURSE.reset();
    }

    static void abandon(EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        if (pendingStart != null && pendingStart.navigator() == navigator) pendingStart = null;
        if (owner != navigator) return;
        if (backend != null) {
            // Embedded BlockBreakHelper records only a local stop request. The actor boundary
            // owns any native receipt invalidation after manual control or world loss.
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
            ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
        }
        ACTIONS.bodyGone();
        BUILD_SCAFFOLDS.reset();
        owner = null; pendingPolicyOwner = null; pendingPolicyGoal = null;
        EmbeddedBaritonePolicy.clear();
        CAMERA_COURSE.reset();
    }

    static boolean hasConcretePath(EmbeddedBaritoneNavigator navigator) {
        return owner == navigator && backend != null
                && backend.getPathingBehavior().getCurrent() != null;
    }

    static boolean planningInFlight(EmbeddedBaritoneNavigator navigator) {
        return owner == navigator && backend != null
                && backend.getPathingBehavior().getCurrent() == null
                && backend.getPathingBehavior().getInProgress().isPresent();
    }

    private static EmbeddedBaritoneNavigator lastDrivenOwner;
    private static long lastDrivenRevision = Long.MIN_VALUE;

    static org.maiwithu.maicraft.core.pathing.execute.NavigationStep executionStep(
            EmbeddedBaritoneNavigator navigator, long clientRevision) {
        requireClientThread();
        if (owner != navigator || backend == null || lastDrivenOwner != navigator
                || lastDrivenRevision != clientRevision - 1 || ACTIONS.pending()) return null;
        var executor = ((PathingBehavior) backend.getPathingBehavior()).getCurrent();
        if (executor == null) return null;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        if (index < 0 || index >= movements.size()) return null;
        var movement = movements.get(index);
        return new org.maiwithu.maicraft.core.pathing.execute.NavigationStep(movement.getSrc(), movement.getDest());
    }

    /** The driven water route owns normal ascent/refill; the reflex covers idle or suspended bodies. */
    public static boolean managesSwimAir(LocalPlayer player) {
        if (player == null || backend == null || owner == null
                || backend.getPlayerContext().player() != player
                || lastSwimDriveTick == Long.MIN_VALUE
                || player.level().getGameTime() - lastSwimDriveTick > 1
                || !org.maiwithu.maicraft.client.actor.DefaultBodyControlPort.permitsWorldMovement(
                        Minecraft.getInstance().screen)) return false;
        return backend.getPathingBehavior().getCurrent() instanceof PathExecutor executor
                && executor.submergedWaterManagesAir();
    }

    static boolean isSafeToCancel(EmbeddedBaritoneNavigator navigator) {
        return owner != navigator || backend == null
                || ((PathingBehavior) backend.getPathingBehavior()).isSafeToCancel();
    }

    static BlockPos pathStart(EmbeddedBaritoneNavigator navigator, BlockPos fallback) {
        if (owner != navigator || backend == null || backend.getPlayerContext().player() == null) {
            return fallback;
        }
        return ((PathingBehavior) backend.getPathingBehavior()).pathStart();
    }

    static HitResult objectMouseOver(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null || backend.getPlayerContext().player() == null) {
            return null;
        }
        return backend.getPlayerContext().objectMouseOver();
    }

    static MovementFall currentFall(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null) return null;
        var executor = backend.getPathingBehavior().getCurrent();
        if (executor == null) return null;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        return index >= 0 && index < movements.size()
                && movements.get(index) instanceof MovementFall fall ? fall : null;
    }

    /** The scheduled fall retains its item and recovery ownership until the movement has settled. */
    public static boolean ownsActiveLandingAssist(LocalPlayer player) {
        if (player == null || owner == null || backend == null || world != player.clientLevel
                || backend.getPlayerContext().player() != player) return false;
        MovementFall fall = currentFall(owner);
        if (fall == null) return false;
        var assist = fall.landingAssist();
        if (assist != null && (!assist.complete() || assist.cleanupPending())) return true;
        var boat = fall.landingBoat();
        return boat != null && (!boat.failed() || boat.cleanupPending());
    }

    /** Called by the adapted upstream input behavior while the actor lease is open. */
    public static void applyActionState(InputOverrideHandler input) {
        if (BUILD_SCAFFOLDS.pending()) return;
        LocalPlayerContext context = tickingContext;
        EmbeddedBaritoneNavigator current = owner;
        if (context == null || current == null) return;
        ACTIONS.tick(context, current, input);
    }

    /** Compatibility hook for upstream movement code that wants a selected hotbar slot. */
    public static boolean ensureHotbarSelected(LocalPlayer player, int slot) {
        LocalPlayerContext context = tickingContext;
        EmbeddedBaritoneNavigator current = owner;
        return context != null && current != null
                && ACTIONS.ensureHotbarSelected(context, current, player, slot);
    }

    /** Building keeps its permanent materials reserved even if the only spare scaffold is off the hotbar. */
    public static boolean selectBuildScaffold(LocalPlayer player, boolean select) {
        var choice = org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry.scaffoldChoice(player);
        if (choice == null) return false;
        if (!select) return true;
        if (tickingContext == null || owner == null || ACTIONS.pending()) return false;
        if (choice.inventorySlot() < 9 && !BUILD_SCAFFOLDS.pending())
            return ACTIONS.ensureHotbarSelected(tickingContext, owner, player, choice.inventorySlot());
        if (!player.onGround() && !BUILD_SCAFFOLDS.pending()) return false;
        return BUILD_SCAFFOLDS.select(tickingContext, owner, player);
    }

    private static boolean prepareBuildScaffold(LocalPlayerContext context, EmbeddedBaritoneNavigator current) {
        if (BUILD_SCAFFOLDS.pending()) return !BUILD_SCAFFOLDS.advance(context, current);
        if (current.permit() != TerrainPermit.TERRAFORM || !context.player().onGround() || ACTIONS.pending()
                || !org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry.hasScaffoldMaterialPolicy()) return false;
        var choice = org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry.scaffoldChoice(context.player());
        if (choice == null || choice.inventorySlot() < 9) return false;
        // Prepare while grounded, before Baritone can launch a placement-dependent jump.
        return !BUILD_SCAFFOLDS.select(context, current, context.player());
    }

    /** Compatibility hook for upstream cancellation sites; no game-mode call occurs here. */
    public static void requestStopBreaking() {
        ACTIONS.requestStopBreaking();
    }

    /** Called by the adapted upstream input behavior while the actor lease is open. */
    // 把 Baritone 请求的按键交给角色输入层；游泳、收回落地用品或乘船救援有更具体的控制要求时，采用这些要求。
    public static void applyInputState(InputOverrideHandler input) {
        if (backend == null || owner == null) return;
        var player = backend.getPlayerContext().player();
        if (player == null) return;
        if (BUILD_SCAFFOLDS.pending()) { InputDriver.halt(player); return; }
        var currentExecutor = backend.getPathingBehavior().getCurrent();
        PathExecutor executor = currentExecutor instanceof PathExecutor pathExecutor
                ? pathExecutor : null;
        float forward = (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD) ? 1F : 0F)
                - (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_BACK) ? 1F : 0F);
        float strafe = (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_LEFT) ? 1F : 0F)
                - (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_RIGHT) ? 1F : 0F);
        boolean jump = input.isInputForcedDown(baritone.api.utils.input.Input.JUMP);
        boolean sneak = input.isInputForcedDown(baritone.api.utils.input.Input.SNEAK);
        boolean sprint = input.isInputForcedDown(baritone.api.utils.input.Input.SPRINT)
                || (executor != null && executor.isSprinting());
        // The swim phase owns sprinting as well as depth: rising/refilling needs an upright body.
        if (player.isInWater()) {
            if (executor != null && executor.submergedWaterTravelActive()) {
                sprint = executor.submergedWaterSprinting() && forward > 0.5F;
            } else if (forward > 0.5F) sprint = true;
            if (executor != null) {
                int vertical = executor.waterVerticalIntent();
                if (vertical < 0) {
                    sneak = true;
                    jump = false;
                } else if (vertical > 0) {
                    jump = true;
                    sneak = false;
                } else if (executor.submergedWaterTravelActive()) {
                    // The upstream movement's generic water bob requests JUMP. While the selected
                    // route owns a deliberate cruise depth, neutral vertical intent means hold
                    // that depth, not surface.
                    jump = false;
                    sneak = false;
                }
            }
        }
        MovementFall assistedFall = currentFall(owner);
        if (tickingContext != null && assistedFall != null && assistedFall.landingAssist() != null
                && assistedFall.landingAssist().holdingForRecovery(tickingContext)) {
            // Generic swimming must not jump out of the clutch water or fight its recovery aim.
            forward = 0; strafe = 0; jump = false; sneak = assistedFall.landingAssist().wantsSneak(tickingContext); sprint = false;
        }
        var landingMovement = assistedFall != null && assistedFall.landingBoat() != null
                ? assistedFall.landingBoat().movementOverride() : null;
        // Boat.interact refuses secondary use. Release the ordinary fall's edge-crouch before
        // the next player physics/input tick, not only after entering the mount window.
        if(assistedFall!=null && assistedFall.landingBoat()!=null) sneak=assistedFall.landingBoat().wantsSneak();
        else if(tickingContext!=null && assistedFall!=null && assistedFall.landingAssist()!=null
                && assistedFall.landingAssist().plan().kind()==org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan.Kind.BOAT)
            sneak=assistedFall.landingAssist().wantsSneak(tickingContext);
        if (landingMovement==null && assistedFall!=null && assistedFall.landingAssist()!=null)
            landingMovement=assistedFall.landingAssist().movementOverride();
        if (landingMovement != null) {
            forward = landingMovement.forward(); strafe = landingMovement.strafe();
            jump = landingMovement.jumping(); sneak = landingMovement.sneaking(); sprint = landingMovement.sprinting();
        }
        if (tickingContext!=null && assistedFall!=null && assistedFall.landingAssist()!=null
                && !player.onGround() && !player.isPassenger() && landingMovement==null
                && !assistedFall.landingAssist().holdingForRecovery(tickingContext)) {
            var landing=assistedFall.landingAssist().plan().feet(); final boolean crouch=sneak;
            tickingContext.body().applySteering(yaw -> org.maiwithu.maicraft.core.pathing.baritone.landing.AirLandingControl
                    .movement(player,landing,yaw,crouch),player.getYRot(),tickingContext.tickRevision());
            return;
        }
        InputDriver.applyMovement(player, forward, strafe, jump, sneak, sprint);
    }

    /** Called by adapted LookBehavior; DefaultBodyControlPort remains the only camera owner.
     *
     * <p>Movement aims hold a committed course instead of chasing Baritone's per-tick cell
     * aim. That raw stream pitches down toward cell centers below eye level, steepening as
     * the body closes in and snapping back at every movement handoff — a visible per-block
     * bob with the head held down the entire trip. Here the camera locks to a course yaw;
     * bearing corrections inside the course's turn window do not rotate the visible
     * camera. Physical movement yaw is supplied independently by Baritone's player-rotation
     * bridge, so the camera never decomposes that correction into a second steering input.
     * Only a real corner re-commits the visible course and swings the camera once.
     * While grounded the pitch rests at a near-level scenic angle; airborne it follows the
     * aim so falls and jumps keep their control. Precision block-interaction aims bypass
     * all of this so digging and placing keep exact rotations.</p>
     */
    // 实际点击所需的精确瞄准直接交给视角层；普通走路用缓慢改变的路线朝向，地面视角保持略向下。
    public static void requestLook(float yaw, float pitch, boolean precisionAim) {
        if (backend == null || owner == null || backend.getPlayerContext().player() == null) return;
        var player = backend.getPlayerContext().player();
        if (precisionAim) {
            InputDriver.look(player, yaw, pitch);
            return;
        }
        var currentExecutor = backend.getPathingBehavior().getCurrent();
        PathExecutor executor = currentExecutor instanceof PathExecutor pathExecutor
                ? pathExecutor : null;
        if (executor != null && executor.submergedWaterTravelActive()) {
            pitch = executor.submergedWaterCameraPitch();
        }
        if (tickingContext == null) return;
        float courseYaw = CAMERA_COURSE.target(yaw, tickingContext.tickRevision());
        // Re-issued every tick even when unchanged: the look lease must stay fresh or the
        // camera would freeze on whatever the last precision aim left it on.
        float walkPitch = player.onGround()
                && !(executor != null && executor.submergedWaterTravelActive())
                ? WALK_PITCH_DEGREES
                : Mth.clamp(pitch, -90.0f, 90.0f);
        InputDriver.look(player, courseYaw, walkPitch);
    }

    /** Course-steering state; reset whenever navigation ownership changes. */
    private static final NavigationCameraCourse CAMERA_COURSE = new NavigationCameraCourse();
    /** Grounded walking pitch — near level, so the ride never reads as head-down. */
    private static final float WALK_PITCH_DEGREES = 8.0f;

    private static IBaritone backend() {
        if (backend != null) return backend;
        IBaritone created = BaritoneAPI.getProvider().getPrimaryBaritone();
        created.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                EmbeddedBaritoneNavigator current = owner;
                if (current != null) current.onPathEvent(event);
            }
        });
        backend = created;
        return created;
    }

    private static void configure(
            IBaritone baritone,
            TerrainPermit permit,
            boolean sprintAllowed) {
        Settings settings = BaritoneAPI.getSettings();
        settings.chatControl.value = false;
        settings.chatControlAnyway.value = false;
        settings.renderPath.value = false;
        settings.renderGoal.value = false;
        settings.notificationOnPathComplete.value = false;
        settings.disconnectOnArrival.value = false;
        settings.freeLook.value = false;
        settings.smoothLook.value = false;
        settings.randomLooking.value = 0D;
        settings.randomLooking113.value = 0D;
        settings.allowSprint.value = sprintAllowed;
        settings.sprintAscends.value = true;
        settings.sprintInWater.value = true;
        // 非放置型跑酷(跨洞跳跃)不改动地形,PRESERVE 下也安全;放置型跑酷仍由
        // permit 门控(allowParkourPlace)。
        settings.allowParkour.value = true;
        configureTerrain(settings, permit);
        // 当前关闭 Baritone 自行整理普通背包的行为；这条桥只直接支持已在快捷栏中的选择，不能据携带总量推定马上可用。
        settings.allowInventory.value = false;
        settings.acceptableThrowawayItems.value = ScaffoldMaterials.of(
                baritone.getPlayerContext().player());
        settings.logger.value = message -> Constants.LOG.debug(
                "[embedded-path] {}", message.getString());
    }

    // 把本次许可明确写到 Baritone：能否挖、能否搭路、能否用水桶分别从这份许可决定，不沿用上一次导航的状态。
    static void configureTerrain(Settings settings, TerrainPermit permit) {
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.configure(permit);
        settings.allowBreak.value = permit.mayAlter();
        settings.allowBreakAnyway.value = List.of();
        settings.allowPlace.value = permit.mayAlter();
        settings.allowParkourPlace.value = permit.mayAlter();
        settings.allowDownward.value = permit.mayAlter();
        // Keep the legacy setting aligned with the route request. Automatic self-rescue captures
        // its separate landing-only policy and does not authorize general terrain modification.
        settings.allowWaterBucketFall.value = permit.mayUseWaterBucket();
    }

    private static void syncWorld(IBaritone baritone, ClientLevel current) {
        if (world == current) return;
        physicalObstacles = org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot.EMPTY;
        physicalObservationOrigin = null;
        baritone.getGameEventHandler().onWorldEvent(
                new WorldEvent(current, EventState.POST));
        world = current;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("embedded Baritone is client-thread owned");
        }
    }

    private static LocalPlayerContext currentContext() {
        LocalPlayerContext current = tickingContext;
        if (current != null && current.isCurrent()) return current;
        return ClientRuntime.actor().activeContext().orElse(null);
    }

    private static void queuePendingStart(PendingStart next) {
        PendingStart queued = pendingStart;
        if (queued != null && queued.navigator() != next.navigator()) {
            queued.navigator().preempted(
                    "a newer MaiCraft navigation superseded it before body acquisition");
        }
        pendingStart = next;
    }

    private static void promotePendingStart(IBaritone baritone) {
        if (owner != null || pendingStart == null) return;
        PendingStart next = pendingStart;
        pendingStart = null;
        if (!next.navigator().canAcquireRuntimeOwnership()) return;

        EmbeddedBaritoneNavigator navigator = next.navigator();
        owner = navigator;
        configure(baritone, next.permit(), next.sprintAllowed());
        // Activation may happen after the semantic parent's ThreadLocal scope has closed.
        // The queued request owns the immutable policy captured when that parent requested it.
        EmbeddedBaritonePolicy.installSnapshot(next.policy());
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        CAMERA_COURSE.reset();
        refreshPhysicalObstacles(baritone.getPlayerContext().player());
        baritone.getCustomGoalProcess().setGoalAndPath(
                new MaiCraftGoalAdapter(next.compiled().goal()));
    }

    private static void replaceActiveRoute(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            LocalPlayerContext context,
            String reason) {
        if (backend == null || owner != navigator) return;
        ACTIONS.suspend(context, navigator, reason);
        backend.getPathingBehavior().forceCancel();
        ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
        backend.getCustomGoalProcess().setGoalAndPath(
                new MaiCraftGoalAdapter(compiled.goal()));
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
    }
}
