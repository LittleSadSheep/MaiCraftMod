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
 * The one embedded Baritone body clock.
 *
 * <p>Upstream's Minecraft tick mixin is intentionally not registered. MaiCraft calls this once,
 * after its semantic winner has run and while the actor lease is still open. That prevents both
 * double ticking and a second {@code LocalPlayer.input} owner.</p>
 */
public final class EmbeddedBaritoneRuntime {
    private static long lastSwimDriveTick = Long.MIN_VALUE;
    private static final EmbeddedBaritoneActionBridge ACTIONS =
            new EmbeddedBaritoneActionBridge();
    private static IBaritone backend;
    private static EmbeddedBaritoneNavigator owner;
    private static ClientLevel world;
    private static LocalPlayerContext tickingContext;
    private static EmbeddedBaritoneNavigator pendingPolicyOwner;
    private static GoalCompiler.Compiled pendingPolicyGoal;
    private static PendingStart pendingStart;

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
                            navigator.protectedMutationCells(), navigator.forbiddenBodyCells()));
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

    static void startOrUpdate(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            TerrainPermit permit,
            boolean sprintAllowed) {
        requireClientThread();
        IBaritone baritone = backend();
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
                navigator.forbiddenBodyCells());
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
                navigator.forbiddenBodyCells());
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
        boolean drive = current.requiresOrphanContinuation()
                || (schedulerAllowsBodyWork && requested);
        if (!drive) {
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
            lastSwimDriveTick = context.level().getGameTime();
            tickingContext = context;
            BiFunction<EventState, TickEvent.Type, TickEvent> events =
                    TickEvent.createNextProvider();
            baritone.getGameEventHandler().onTick(
                    events.apply(EventState.PRE, TickEvent.Type.IN));
            baritone.getGameEventHandler().onPostTick(
                    events.apply(EventState.POST, TickEvent.Type.IN));
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

    static void release(EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        if (owner != navigator) return;
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
        courseCommitted = false;
        courseTurning = false;
        owner = null;
    }

    public static void bodyGone() {
        requireClientThread();
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
        owner = null;
        world = null;
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        pendingStart = null;
        EmbeddedBaritonePolicy.clear();
        courseCommitted = false;
        courseTurning = false;
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

    /** Called by the adapted upstream input behavior while the actor lease is open. */
    public static void applyActionState(InputOverrideHandler input) {
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

    /** Compatibility hook for upstream cancellation sites; no game-mode call occurs here. */
    public static void requestStopBreaking() {
        ACTIONS.requestStopBreaking();
    }

    /** Called by the adapted upstream input behavior while the actor lease is open. */
    public static void applyInputState(InputOverrideHandler input) {
        if (backend == null || owner == null) return;
        var player = backend.getPlayerContext().player();
        if (player == null) return;
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
        InputDriver.applyMovement(player, forward, strafe, jump, sneak, sprint);
    }

    /** Called by adapted LookBehavior; DefaultBodyControlPort remains the only camera owner.
     *
     * <p>Movement aims hold a committed course instead of chasing Baritone's per-tick cell
     * aim. That raw stream pitches down toward cell centers below eye level, steepening as
     * the body closes in and snapping back at every movement handoff — a visible per-block
     * bob with the head held down the entire trip. Here the camera locks to a course yaw;
     * bearing corrections inside {@link #COURSE_TURN_WINDOW_DEGREES} do not rotate the visible
     * camera. Physical movement yaw is supplied independently by Baritone's player-rotation
     * bridge, so the camera never decomposes that correction into a second steering input.
     * Only a real corner re-commits the visible course and swings the camera once.
     * While grounded the pitch rests at a near-level scenic angle; airborne it follows the
     * aim so falls and jumps keep their control. Precision block-interaction aims bypass
     * all of this so digging and placing keep exact rotations.</p>
     */
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
        if (!courseCommitted) {
            courseYaw = Mth.wrapDegrees(yaw);
            courseCommitted = true;
            courseTurning = false;
        }
        // The course holds while corrections fit the strafe window; only a real turn
        // rotates it — ramped at a bounded rate with hysteresis (past the window to
        // engage, well inside it to settle). A recalc that flips the aim back and
        // forth (drop edges, pocket grinds) then averages into one smooth rotation
        // instead of banging the camera side to side or spinning it around.
        float bearingError = Mth.wrapDegrees(yaw - courseYaw);
        if (Math.abs(bearingError) > COURSE_TURN_WINDOW_DEGREES) {
            courseTurning = true;
        } else if (courseTurning && Math.abs(bearingError) <= COURSE_SETTLE_DEGREES) {
            courseTurning = false;
        }
        if (courseTurning) {
            courseYaw = Mth.wrapDegrees(courseYaw + Mth.clamp(bearingError,
                    -COURSE_TURN_STEP_DEGREES, COURSE_TURN_STEP_DEGREES));
        }
        // Re-issued every tick even when unchanged: the look lease must stay fresh or the
        // camera would freeze on whatever the last precision aim left it on.
        float walkPitch = player.onGround()
                && !(executor != null && executor.submergedWaterTravelActive())
                ? WALK_PITCH_DEGREES
                : Mth.clamp(pitch, -90.0f, 90.0f);
        InputDriver.look(player, courseYaw, walkPitch);
    }

    /** Course-steering state; reset whenever navigation ownership changes. */
    private static boolean courseCommitted;
    private static boolean courseTurning;
    private static float courseYaw;
    /** Bearing corrections beyond this window rotate the course (a real corner); smaller
     * ones are absorbed as strafe input, keeping the view steady. */
    private static final float COURSE_TURN_WINDOW_DEGREES = 25.0f;
    /** A turn settles (stops rotating the course) once inside this margin. */
    private static final float COURSE_SETTLE_DEGREES = 15.0f;
    /** Course rotation rate cap: 9°/tick = 180°/s, so a 90° corner takes ~0.5s. */
    private static final float COURSE_TURN_STEP_DEGREES = 9.0f;
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
        settings.allowInventory.value = false;
        settings.acceptableThrowawayItems.value = ScaffoldMaterials.of(
                baritone.getPlayerContext().player());
        settings.logger.value = message -> Constants.LOG.debug(
                "[embedded-path] {}", message.getString());
    }

    static void configureTerrain(Settings settings, TerrainPermit permit) {
        settings.allowBreak.value = permit.mayAlter();
        settings.allowBreakAnyway.value = List.of();
        settings.allowPlace.value = permit.mayAlter();
        settings.allowParkourPlace.value = permit.mayAlter();
        settings.allowDownward.value = permit.mayAlter();
        // A clutch is a block/fluid placement. Preserve navigation may swim or fall safely, but
        // it must never turn an unapproved route into a water-placement route.
        settings.allowWaterBucketFall.value = permit.mayUseWaterBucket();
    }

    private static void syncWorld(IBaritone baritone, ClientLevel current) {
        if (world == current) return;
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
        courseCommitted = false;
        courseTurning = false;
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
