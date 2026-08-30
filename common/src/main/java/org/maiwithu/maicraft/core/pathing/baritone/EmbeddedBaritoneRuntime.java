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
import baritone.utils.InputOverrideHandler;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
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
    private static IBaritone backend;
    private static EmbeddedBaritoneNavigator owner;
    private static ClientLevel world;

    private EmbeddedBaritoneRuntime() {}

    static void startOrUpdate(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            TerrainPermit permit,
            boolean sprintAllowed) {
        requireClientThread();
        IBaritone baritone = backend();
        if (owner != null && owner != navigator) {
            owner.preempted("another MaiCraft navigation acquired the first-person body");
            baritone.getPathingBehavior().forceCancel();
        }
        owner = navigator;
        courseCommitted = false;
        courseTurning = false;
        pendingSteerError = Float.NaN;
        configure(baritone, permit, sprintAllowed);
        refreshPolicy(navigator, compiled);
        baritone.getCustomGoalProcess().setGoalAndPath(
                new MaiCraftGoalAdapter(compiled.goal()));
    }

    static void refreshPolicy(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled) {
        if (owner != navigator) return;
        EmbeddedBaritonePolicy.install(
                compiled.sacred(),
                NavigationSafetyContext.protectedMutationCells(),
                NavigationSafetyContext.forbiddenBodyCells());
    }

    /** Called exactly once at the end of an open MaiCraft actor tick. */
    public static void tick(LocalPlayerContext context, boolean schedulerAllowsBodyWork) {
        requireClientThread();
        IBaritone baritone = backend;
        if (baritone == null) return;
        syncWorld(baritone, context.level());

        EmbeddedBaritoneNavigator current = owner;
        boolean drive = schedulerAllowsBodyWork && current != null
                && current.consumeDriveRequest();
        if (!drive) {
            baritone.getInputOverrideHandler().clearAllKeys();
            InputDriver.halt(context.player());
            return;
        }

        try {
            BiFunction<EventState, TickEvent.Type, TickEvent> events =
                    TickEvent.createNextProvider();
            baritone.getGameEventHandler().onTick(
                    events.apply(EventState.PRE, TickEvent.Type.IN));
            baritone.getGameEventHandler().onPostTick(
                    events.apply(EventState.POST, TickEvent.Type.IN));
        } catch (RuntimeException failure) {
            Constants.LOG.error("[maicraft-path] embedded Baritone tick failed", failure);
            current.preempted("embedded pathing tick failed: "
                    + failure.getClass().getSimpleName());
            release(current);
        }
    }

    static void suspend(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null) return;
        backend.getInputOverrideHandler().clearAllKeys();
    }

    static void release(EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        if (owner != navigator) return;
        if (backend != null) {
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
        }
        EmbeddedBaritonePolicy.clear();
        courseCommitted = false;
        courseTurning = false;
        pendingSteerError = Float.NaN;
        owner = null;
    }

    public static void bodyGone() {
        requireClientThread();
        if (owner != null) owner.preempted("the local-player body or world disappeared");
        if (backend != null) {
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            backend.getGameEventHandler().onWorldEvent(
                    new WorldEvent(null, EventState.POST));
        }
        owner = null;
        world = null;
        EmbeddedBaritonePolicy.clear();
        courseCommitted = false;
        courseTurning = false;
        pendingSteerError = Float.NaN;
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

    /** Called by the adapted upstream input behavior while the actor lease is open. */
    public static void applyInputState(InputOverrideHandler input) {
        if (backend == null || owner == null) return;
        var player = backend.getPlayerContext().player();
        if (player == null) return;
        float forward = (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD) ? 1F : 0F)
                - (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_BACK) ? 1F : 0F);
        float strafe = (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_LEFT) ? 1F : 0F)
                - (input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_RIGHT) ? 1F : 0F);
        boolean jump = input.isInputForcedDown(baritone.api.utils.input.Input.JUMP);
        boolean sneak = input.isInputForcedDown(baritone.api.utils.input.Input.SNEAK);
        boolean sprint = input.isInputForcedDown(baritone.api.utils.input.Input.SPRINT)
                || (backend.getPathingBehavior().getCurrent() != null
                && ((baritone.pathing.path.PathExecutor)
                        backend.getPathingBehavior().getCurrent()).isSprinting());
        // 水中疾跑:Baritone 只在脚下有实心"桥面"时才请求疾跑,深水过河会退化成
        // 无疾跑的水面扑腾(~2 格/秒)。水中按住前进就强制疾跑——原版疾跑爬泳约
        // 5.3 格/秒,与潜泳相当,且身体贴着水面路径格,不破坏移动的完成判定。
        if (player.isInWater() && forward > 0.5F) {
            sprint = true;
        }
        // Course steering: a small bearing error between the held camera course and the
        // movement's aim is absorbed as strafe (the human W+A / W+D habit) instead of
        // rotating the view. Decomposition keeps the impulse vector at unit length (no
        // speed loss) and forward >= cos(window), so sprint survives the correction.
        if (forward > 0.5F && strafe == 0F && !Float.isNaN(pendingSteerError)) {
            forward = (float) Math.cos(pendingSteerError);
            strafe = -(float) Math.sin(pendingSteerError);
        }
        InputDriver.applyMovement(player, forward, strafe, jump, sneak, sprint);
    }

    /** Called by adapted LookBehavior; DefaultBodyControlPort remains the only camera owner.
     *
     * <p>Movement aims hold a committed course instead of chasing Baritone's per-tick cell
     * aim. That raw stream pitches down toward cell centers below eye level, steepening as
     * the body closes in and snapping back at every movement handoff — a visible per-block
     * bob with the head held down the entire trip. Here the camera locks to a course yaw;
     * bearing corrections inside {@link #COURSE_TURN_WINDOW_DEGREES} never rotate the view —
     * they are decomposed into strafe input by {@link #applyInputState} (the human W+A /
     * W+D habit). Only a real corner re-commits the course and swings the camera once.
     * While grounded the pitch rests at a near-level scenic angle; airborne it follows the
     * aim so falls and jumps keep their control. Precision block-interaction aims bypass
     * all of this so digging and placing keep exact rotations.</p>
     */
    public static void requestLook(float yaw, float pitch, boolean precisionAim) {
        if (backend == null || owner == null || backend.getPlayerContext().player() == null) return;
        var player = backend.getPlayerContext().player();
        if (precisionAim) {
            pendingSteerError = Float.NaN;
            InputDriver.look(player, yaw, pitch);
            return;
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
        float steerError = Mth.wrapDegrees(yaw - courseYaw);
        pendingSteerError = Math.abs(steerError) <= COURSE_TURN_WINDOW_DEGREES
                ? (float) Math.toRadians(steerError)
                : Float.NaN;
        // Re-issued every tick even when unchanged: the look lease must stay fresh or the
        // camera would freeze on whatever the last precision aim left it on.
        float walkPitch = player.onGround()
                ? WALK_PITCH_DEGREES
                : Mth.clamp(pitch, -90.0f, 90.0f);
        InputDriver.look(player, courseYaw, walkPitch);
    }

    /** Course-steering state; reset whenever navigation ownership changes. */
    private static boolean courseCommitted;
    private static boolean courseTurning;
    private static float courseYaw;
    private static float pendingSteerError = Float.NaN;
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
        settings.sprintInWater.value = true;
        // 非放置型跑酷(跨洞跳跃)不改动地形,PRESERVE 下也安全;放置型跑酷仍由
        // permit 门控(allowParkourPlace)。
        settings.allowParkour.value = true;
        settings.allowBreak.value = permit.mayAlter();
        settings.allowPlace.value = permit.mayAlter();
        settings.allowParkourPlace.value = permit.mayAlter();
        settings.allowDownward.value = permit.mayAlter();
        // A clutch is a block/fluid placement. Preserve navigation may swim or fall safely, but
        // it must never turn an unapproved route into a water-placement route.
        settings.allowWaterBucketFall.value = permit.mayAlter();
        settings.allowInventory.value = false;
        settings.acceptableThrowawayItems.value = ScaffoldMaterials.of(
                baritone.getPlayerContext().player());
        settings.logger.value = message -> Constants.LOG.debug(
                "[embedded-path] {}", message.getString());
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
}
