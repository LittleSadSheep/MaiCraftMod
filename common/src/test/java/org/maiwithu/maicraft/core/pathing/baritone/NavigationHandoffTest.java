package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BlockBreakHelper;
import baritone.utils.InputOverrideHandler;
import baritone.utils.PathingControlManager;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import sun.misc.Unsafe;

/** Real navigator lifecycle and Baritone input cleanup; no client window or world is created. */
public final class NavigationHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ClientRuntime.actor(); // Preserve its original binding before the fixture swaps Minecraft.
        pollingYieldRetainsDrive();
        abandonedCallerStillReachesTheSafeBoundary(false);
        abandonedCallerStillReachesTheSafeBoundary(true);
        System.out.println("NavigationHandoffTest: passed");
    }

    private static void pollingYieldRetainsDrive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // The mining caller polls yield only; it never calls nav.tick().
            for (int tick = 0; tick < 3; tick++) {
                check(!fixture.nav.yieldForExternalAction(), "unsafe movement yielded to a destructive action");
                check(fixture.nav.requiresOrphanContinuation(), "yield did not latch the existing pending pause");
                check(fixture.nav.consumeDriveRequest(), "yield-only caller starved the selected movement");
                check(fixture.inputs.isInputForcedDown(Input.MOVE_FORWARD), "unsafe yield cleared movement input");
            }
            fixture.finishMovement();
            check(!fixture.inputs.isInputForcedDown(Input.MOVE_FORWARD), "safe handoff did not clear movement input");
            check(!fixture.nav.requiresOrphanContinuation() && !fixture.nav.consumeDriveRequest(),
                    "completed pause continued to drive after the external action could take over");
        }
    }

    private static void abandonedCallerStillReachesTheSafeBoundary(boolean stop) throws Exception {
        try (Fixture fixture = new Fixture()) {
            if (stop) fixture.nav.stop();
            else check(!fixture.nav.yieldForExternalAction(), "unsafe yield unexpectedly completed");
            // After the caller disappears, use only the runtime's continuation entry points.
            check(fixture.nav.consumeDriveRequest(), "initial continuation was not requested");
            check(!fixture.nav.consumeDriveRequest(), "drive flag must remain a one-tick request");
            for (int tick = 0; tick < 3; tick++) {
                check(fixture.nav.requiresOrphanContinuation(), "caller disappearance starved unsafe movement");
                fixture.nav.settlePendingFailureAtSafeBoundary();
                check(fixture.inputs.isInputForcedDown(Input.MOVE_FORWARD), "cleanup halted before reaching safety");
            }
            fixture.finishMovement();
            check(!fixture.nav.requiresOrphanContinuation(), "settled navigation retained orphan continuation");
            check(!fixture.inputs.isInputForcedDown(Input.MOVE_FORWARD), "safe boundary did not stop the body");
            if (stop) {
                check(field(EmbeddedBaritoneRuntime.class, "owner").get(null) == null,
                        "completed stop retained the navigation owner");
                check(fixture.nav.tick() == PlayerNav.Status.FAILED, "stopped navigation resumed a new route");
            } else {
                check(field(EmbeddedBaritoneRuntime.class, "owner").get(null) == fixture.nav,
                        "temporary yield discarded the reusable navigation owner");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Object previousMinecraft = field(Minecraft.class, "instance").get(null);
        private final Object previousActorClient = field(ClientRuntime.actor().getClass(), "minecraft")
                .get(ClientRuntime.actor());
        private final Object previousOwner = field(EmbeddedBaritoneRuntime.class, "owner").get(null);
        private final Object previousBackend = field(EmbeddedBaritoneRuntime.class, "backend").get(null);
        private final Unsafe memory;
        private final PathingBehavior pathing;
        private final InputOverrideHandler inputs;
        private final EmbeddedBaritoneNavigator nav;

        private Fixture() throws Exception {
            memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            Minecraft minecraft = allocate(Minecraft.class);
            minecraft.player = allocate(LocalPlayer.class); // Distinct from the unused null navigator player.
            set(minecraft, "gameThread", Thread.currentThread());
            field(Minecraft.class, "instance").set(null, minecraft);
            field(ClientRuntime.actor().getClass(), "minecraft").set(ClientRuntime.actor(), minecraft);
            Baritone baritone = allocate(Baritone.class);
            pathing = allocate(PathingBehavior.class);
            inputs = allocate(InputOverrideHandler.class);
            set(inputs, "inputForceStateMap", new HashMap<>());
            set(inputs, "blockBreakHelper", allocate(BlockBreakHelper.class));
            LookBehavior look = allocate(LookBehavior.class);
            set(look, "smoothYawBuffer", new ArrayDeque<>());
            set(look, "smoothPitchBuffer", new ArrayDeque<>());
            PathingControlManager controls = allocate(PathingControlManager.class);
            set(controls, "active", new ArrayList<>());
            set(controls, "processes", new HashSet<>());
            set(baritone, "pathingBehavior", pathing);
            set(baritone, "inputOverrideHandler", inputs);
            set(baritone, "lookBehavior", look);
            set(baritone, "pathingControlManager", controls);
            set(pathing, "baritone", baritone);
            set(pathing, "current", allocate(PathExecutor.class));
            set(pathing, "toDispatch", new LinkedBlockingQueue<>());
            set(pathing, "safeToCancel", false);
            nav = new EmbeddedBaritoneNavigator(null, () -> null, () -> false,
                    PlayerNav.ContextProvider.DEFAULT, true);
            field(EmbeddedBaritoneRuntime.class, "owner").set(null, nav);
            field(EmbeddedBaritoneRuntime.class, "backend").set(null, baritone);
            inputs.setInputForceState(Input.MOVE_FORWARD, true);
        }

        private void finishMovement() throws Exception {
            // Safety is normally published after PathExecutor.onTick(). All pause/stop
            // transitions here still execute the real navigator and Baritone cleanup code.
            set(pathing, "safeToCancel", true);
            nav.settlePendingFailureAtSafeBoundary();
        }

        private <T> T allocate(Class<T> type) throws InstantiationException {
            return type.cast(memory.allocateInstance(type));
        }

        public void close() throws Exception {
            field(ClientRuntime.actor().getClass(), "minecraft").set(ClientRuntime.actor(), previousActorClient);
            field(EmbeddedBaritoneRuntime.class, "owner").set(null, previousOwner);
            field(EmbeddedBaritoneRuntime.class, "backend").set(null, previousBackend);
            field(Minecraft.class, "instance").set(null, previousMinecraft);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
