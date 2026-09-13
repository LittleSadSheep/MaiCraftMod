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

/**
 * 检查导航暂让控制和停止时的收尾：动作还没到安全位置就继续驱动，到安全位置才清按键；测试直接设置安全状态。
 */
public final class NavigationHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ClientRuntime.actor(); // Preserve its original binding before the fixture swaps Minecraft.
        pollingYieldRetainsDrive();
        abandonedCallerStillReachesTheSafeBoundary(false);
        abandonedCallerStillReachesTheSafeBoundary(true);
        buildSelectionWaitsForReleasedNavigation();
        buildKeepsAnUnfinishedApproach();
        precisionWalkingDoesNotPlanGapJumps();
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

    private static void precisionWalkingDoesNotPlanGapJumps() throws Exception {
        var constructor = baritone.api.Settings.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var settings = constructor.newInstance();
        // 施工短步、长途移动交替时，每次都覆盖自己的跑酷规则；禁跑酷仍允许普通上台阶和获准搭路。
        for (boolean travel : new boolean[]{true, false, true, false}) {
            EmbeddedBaritoneRuntime.configureWalking(settings, travel);
            EmbeddedBaritoneRuntime.configureTerrain(settings, org.maiwithu.maicraft.core.pathing.moves.TerrainPermit.TERRAFORM);
            check(settings.allowSprint.value == travel && settings.allowParkour.value == travel,
                    "precision walking must not inherit a previous travel route's gap jumps");
            check(settings.allowPlace.value, "avoiding gap jumps must not remove authorized ordinary construction access");
        }
    }

    private static void buildKeepsAnUnfinishedApproach() throws Exception {
        try (var world = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness(); Fixture fixture = new Fixture(world.player)) {
            var target = new org.maiwithu.maicraft.core.task.build.BuildTaskRecord.Target(
                    net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.item.Items.STONE,
                    new net.minecraft.core.BlockPos(4, 1, 4), "nearby during jump", null, null, null);
            var record = new org.maiwithu.maicraft.core.task.build.BuildTaskRecord("active-jump", 1000, java.util.List.of(target), false);
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.build.FirstPersonBuildCompanionTask");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, record.getClass()); constructor.setAccessible(true);
            Object task = constructor.newInstance(world.player, record);
            var cellConstructor = Class.forName(type.getName() + "$CellPlan").getDeclaredConstructor(target.getClass(), java.util.List.class);
            cellConstructor.setAccessible(true); Object cell = cellConstructor.newInstance(target, java.util.List.of());
            field(type, "cell").set(task, cell); field(type, "queue").set(task, new ArrayList<>(java.util.List.of(cell)));
            var navigation = PlayerNav.toGoal(world.player, () -> org.maiwithu.maicraft.core.pathing.calc.NavGoal.exact(target.pos()), .8, () -> false).walkingOnly();
            Object transport = field(PlayerNav.class, "navigator").get(navigation); set(transport, "ground", fixture.nav);
            field(type, "nav").set(task, navigation);
            var phase = field(type, "phase"); Object originalPhase = phase.get(task);
            var approach = type.getDeclaredMethod("placeNavTick"); approach.setAccessible(true);
            // 目标此刻已经可伸手放到，但腾空路线仍未落稳；运行实际施工入口，不能换掉原导航或提前进入选物。
            for (int tick = 0; tick < 3; tick++) {
                world.nextTick(); approach.invoke(task);
                check(field(type, "nav").get(task) == navigation && phase.get(task) == originalPhase
                                && field(type, "cell").get(task) == cell && fixture.nav.consumeDriveRequest(),
                        "an unfinished approach keeps its target, steering and construction phase");
                check(world.blockUses() == 0 && world.itemUses() == 0, "the airborne handoff issues no construction action");
            }
        }
    }

    private static void buildSelectionWaitsForReleasedNavigation() throws Exception {
        try (var world = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness();
             Fixture fixture = new Fixture(world.player)) {
            world.inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
            var target = new org.maiwithu.maicraft.core.task.build.BuildTaskRecord.Target(
                    net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.item.Items.STONE,
                    new net.minecraft.core.BlockPos(4, 1, 4), "navigation handoff", null, null, null);
            var record = new org.maiwithu.maicraft.core.task.build.BuildTaskRecord("handoff", 1000, java.util.List.of(target), false);
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.build.FirstPersonBuildCompanionTask");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, record.getClass()); constructor.setAccessible(true);
            Object task = constructor.newInstance(world.player, record);
            var cellConstructor = Class.forName(type.getName() + "$CellPlan").getDeclaredConstructor(target.getClass(), java.util.List.class);
            cellConstructor.setAccessible(true); field(type, "cell").set(task, cellConstructor.newInstance(target, java.util.List.of()));
            var select = type.getDeclaredMethod("selectItemTick"); select.setAccessible(true);
            fixture.nav.stop();
            for (int tick = 0; tick < 3; tick++) {
                world.nextTick();
                check(select.invoke(task) == org.maiwithu.maicraft.task.TaskState.RUNNING,
                        "construction waits while the stopped navigation still owns an unsafe movement");
                var gate = (org.maiwithu.maicraft.core.task.FirstPersonActionGate) field(type, "selection").get(task);
                check(!gate.started(), "construction must not start a competing inventory transaction before native navigation yields");
                check(fixture.nav.requiresOrphanContinuation() && fixture.inputs.isInputForcedDown(Input.MOVE_FORWARD),
                        "waiting construction must preserve orphan continuation and physical steering to safety");
            }
            fixture.finishMovement(); world.nextTick();
            select.invoke(task);
            check(field(type, "phase").get(task).toString().equals("AIM"),
                    "the same construction resumes item selection once the old route releases ownership");
        }
    }

    // 拼出本测试会触及的导航和按键对象，并在结束时恢复全局状态；没有创建真实客户端窗口。
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

        private Fixture() throws Exception { this(null); }

        private Fixture(LocalPlayer suppliedPlayer) throws Exception {
            memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            Minecraft minecraft = suppliedPlayer == null ? allocate(Minecraft.class) : Minecraft.getInstance();
            if (suppliedPlayer == null) minecraft.player = allocate(LocalPlayer.class); // Unused by the pure navigation cases.
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
            nav = new EmbeddedBaritoneNavigator(suppliedPlayer, () -> null, () -> false,
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
