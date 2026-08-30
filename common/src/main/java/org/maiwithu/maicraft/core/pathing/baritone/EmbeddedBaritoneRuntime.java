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
        InputDriver.applyMovement(player, forward, strafe, jump, sneak, sprint);
    }

    /** Called by adapted LookBehavior; DefaultBodyControlPort remains the only camera owner. */
    public static void requestLook(float yaw, float pitch) {
        if (backend == null || owner == null || backend.getPlayerContext().player() == null) return;
        InputDriver.look(backend.getPlayerContext().player(), yaw, pitch);
    }

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
