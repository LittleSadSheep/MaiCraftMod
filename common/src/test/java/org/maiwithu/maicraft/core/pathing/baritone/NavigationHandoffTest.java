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
import baritone.api.Settings;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import net.minecraft.world.entity.Entity;
import java.util.UUID;

/**
 * 检查导航暂让控制和停止时的收尾：动作还没到安全位置就继续驱动，到安全位置才清按键；测试直接设置安全状态。
 */
public final class NavigationHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ClientRuntime.actor(); // 夹具替换 Minecraft 前，先保留原有角色绑定。
        pollingYieldRetainsDrive();
        abandonedCallerStillReachesTheSafeBoundary(false);
        abandonedCallerStillReachesTheSafeBoundary(true);
        buildSelectionWaitsForReleasedNavigation();
        buildKeepsAnUnfinishedApproach();
        precisionWalkingDoesNotPlanGapJumps();
        replacedBodyDiscardsOrphanRoutes(false);
        replacedBodyDiscardsOrphanRoutes(true);
        System.out.println("NavigationHandoffTest: passed");
    }

    private static void replacedBodyDiscardsOrphanRoutes(boolean pendingBelongsToNewBody) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            var old = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
            field(LocalPlayer.class, "clientLevel").set(old, world.level);
            UUID identity = UUID.randomUUID(); field(Entity.class, "uuid").set(old, identity); field(Entity.class, "uuid").set(world.player, identity);
            try (Fixture fixture = new Fixture(old)) {
                // 同一个UUID在同一世界重生，旧路线还处于不可取消的腾空阶段；新身体不能接着替它落地。
                check(!fixture.nav.belongsTo(world.player), "same UUID cannot reuse an old LocalPlayer route");
                var queued = new EmbeddedBaritoneNavigator(pendingBelongsToNewBody ? world.player : old,
                        () -> null, () -> false, PlayerNav.ContextProvider.DEFAULT, false);
                var pendingType = Class.forName(EmbeddedBaritoneRuntime.class.getName() + "$PendingStart");
                var constructor = pendingType.getDeclaredConstructor(EmbeddedBaritoneNavigator.class, GoalCompiler.Compiled.class, TerrainPermit.class, boolean.class);
                constructor.setAccessible(true);
                Object request = constructor.newInstance(queued, GoalCompiler.standOn(new BlockPos(4, 1, 4)), TerrainPermit.PRESERVE, false);
                field(EmbeddedBaritoneRuntime.class, "pendingStart").set(null, request);
                // 暂时移除活动上下文：若清理偷偷调用任何身体InputDriver，这里就会抛错，重现原来的新任务失败。
                var active = field(ClientRuntime.actor().getClass(), "activeContext"); Object context = active.get(ClientRuntime.actor());
                active.set(ClientRuntime.actor(), null);
                try {
                    EmbeddedBaritoneRuntime.observeBody(world.player);
                    fixture.nav.stop(); fixture.nav.pause();
                    check(fixture.nav.tick() == PlayerNav.Status.FAILED, "old route remains retired without native body access");
                } finally { active.set(ClientRuntime.actor(), context); }
                check(field(EmbeddedBaritoneRuntime.class, "owner").get(null) == null && !fixture.inputs.isInputForcedDown(Input.MOVE_FORWARD),
                        "stale owner and forced inputs are removed before a new task starts");
                check(pendingBelongsToNewBody ? field(EmbeddedBaritoneRuntime.class, "pendingStart").get(null) == request
                                && queued.canAcquireRuntimeOwnership()
                        : field(EmbeddedBaritoneRuntime.class, "pendingStart").get(null) == null && !queued.canAcquireRuntimeOwnership(),
                        "only a pending request belonging to the new body survives cleanup");
                check(world.itemUses() == 0 && world.blockUses() == 0, "body replacement cleanup performs no world interaction");
            }
        }
    }

    private static void pollingYieldRetainsDrive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // 挖矿调用方只轮询 yield，不会调用 nav.tick()。
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
            // 调用方消失后，只能通过运行时的续接入口继续执行。
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
        var constructor = Settings.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var settings = constructor.newInstance();
        // 施工短步、长途移动交替时，每次都覆盖自己的跑酷规则；禁跑酷仍允许普通上台阶和获准搭路。
        for (boolean travel : new boolean[]{true, false, true, false}) {
            EmbeddedBaritoneRuntime.configureWalking(settings, travel);
            EmbeddedBaritoneRuntime.configureTerrain(settings, TerrainPermit.TERRAFORM);
            check(settings.allowSprint.value == travel && settings.allowParkour.value == travel,
                    "precision walking must not inherit a previous travel route's gap jumps");
            check(settings.allowPlace.value, "avoiding gap jumps must not remove authorized ordinary construction access");
        }
    }

    private static void buildKeepsAnUnfinishedApproach() throws Exception {
        try (var world = new InteractionWorldTestHarness(); Fixture fixture = new Fixture(world.player)) {
            var target = new BuildTaskRecord.Target(
                    Blocks.STONE, Items.STONE,
                    new BlockPos(4, 1, 4), "nearby during jump", null, null, null);
            var record = new BuildTaskRecord("active-jump", 1000, List.of(target), false);
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.build.FirstPersonBuildCompanionTask");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, record.getClass()); constructor.setAccessible(true);
            Object task = constructor.newInstance(world.player, record);
            var cellConstructor = Class.forName(type.getName() + "$CellPlan").getDeclaredConstructor(target.getClass(), List.class);
            cellConstructor.setAccessible(true); Object cell = cellConstructor.newInstance(target, List.of());
            field(type, "cell").set(task, cell); field(type, "queue").set(task, new ArrayList<>(List.of(cell)));
            var navigation = PlayerNav.toGoal(world.player, () -> NavGoal.exact(target.pos()), .8, () -> false).walkingOnly();
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
        try (var world = new InteractionWorldTestHarness();
             Fixture fixture = new Fixture(world.player)) {
            // 交接测试也提供真实可达的地面点击；导航释放后要重证这一放法，不能用空见证直接进入瞄准。
            world.position(new Vec3(3.12, 1, 4.8));
            world.inventory.setItem(0, new ItemStack(Items.STONE));
            var target = new BuildTaskRecord.Target(
                    Blocks.STONE, Items.STONE,
                    new BlockPos(4, 1, 4), "navigation handoff", null, null, null);
            var record = new BuildTaskRecord("handoff", 1000, List.of(target), false);
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.build.FirstPersonBuildCompanionTask");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, record.getClass()); constructor.setAccessible(true);
            Object task = constructor.newInstance(world.player, record);
            var cellConstructor = Class.forName(type.getName() + "$CellPlan").getDeclaredConstructor(target.getClass(), List.class);
            cellConstructor.setAccessible(true); field(type, "cell").set(task, cellConstructor.newInstance(target, List.of()));
            var geometry = Class.forName("org.maiwithu.maicraft.core.task.build.BuildPlacementGeometry");
            var current = geometry.getDeclaredMethod("currentGesture", LocalPlayer.class, target.getClass(), Map.class);
            current.setAccessible(true);
            field(type, "gesture").set(task, current.invoke(null, world.player, target, Map.of()));
            var select = type.getDeclaredMethod("selectItemTick"); select.setAccessible(true);
            fixture.nav.stop();
            for (int tick = 0; tick < 3; tick++) {
                world.nextTick();
                check(select.invoke(task) == TaskState.RUNNING,
                        "construction waits while the stopped navigation still owns an unsafe movement");
                var gate = (FirstPersonActionGate) field(type, "selection").get(task);
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
        private final Object previousWorld = field(EmbeddedBaritoneRuntime.class, "world").get(null);
        private final Object previousPending = field(EmbeddedBaritoneRuntime.class, "pendingStart").get(null);
        private final Unsafe memory;
        private final PathingBehavior pathing;
        private final InputOverrideHandler inputs;
        private final EmbeddedBaritoneNavigator nav;

        private Fixture() throws Exception { this(null); }

        private Fixture(LocalPlayer suppliedPlayer) throws Exception {
            memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            Minecraft minecraft = suppliedPlayer == null ? allocate(Minecraft.class) : Minecraft.getInstance();
            // 纯导航用例没有身体；需要验证重生的用例保留InteractionWorld夹具的当前玩家。
            if (suppliedPlayer == null) minecraft.player = null;
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
            field(EmbeddedBaritoneRuntime.class, "world").set(null, suppliedPlayer == null ? null : suppliedPlayer.clientLevel);
            inputs.setInputForceState(Input.MOVE_FORWARD, true);
        }

        private void finishMovement() throws Exception {
            // 安全状态通常在 PathExecutor.onTick() 后发布。此处所有暂停和停止转换仍会执行真实导航器及 Baritone 清理代码。
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
            field(EmbeddedBaritoneRuntime.class, "world").set(null, previousWorld);
            field(EmbeddedBaritoneRuntime.class, "pendingStart").set(null, previousPending);
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
