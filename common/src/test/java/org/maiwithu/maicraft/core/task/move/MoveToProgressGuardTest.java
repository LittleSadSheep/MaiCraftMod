// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.DefaultLocalPlayerContext;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.calc.PlanningWorkProgress;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportNavigator;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.GoToThenDoTask;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;
import it.unimi.dsi.fastutil.longs.LongSets;
import baritone.api.event.events.PathEvent;

/**
 * 接近与移动的进度记分牌契约：接近规划必须有界终态、停滞期必须有心跳事件、
 * 剩余计数口径向上取整（剩余 0 只出现在真实到达）。全部走惰性身体，不启动真实搜索。
 */
public final class MoveToProgressGuardTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        quantizedRemainingScale();
        approachPlanningStallTerminatesBounded(memory);
        approachPlanningWithProgressKeepsRunning(memory);
        approachProgressEmitsScoreboardHeartbeat(memory);
        moveToPlanningHeartbeat(memory);
        System.out.println("MoveToProgressGuardTest: passed");
    }

    /** 量化口径：向上取整，半档不再提前报 0；零与负数只按已到达处理。 */
    private static void quantizedRemainingScale() {
        check(AbstractCompanionTask.quantizedRemaining(0) == 0, "distance zero must report remaining zero");
        check(AbstractCompanionTask.quantizedRemaining(-1) == 0, "negative distance must report remaining zero");
        check(AbstractCompanionTask.quantizedRemaining(2) == 16,
                "two-block hop must not report 0 remaining while the body still has to move");
        check(AbstractCompanionTask.quantizedRemaining(36) == 48,
                "36-block route must not understate its scale (round would claim 32)");
        check(AbstractCompanionTask.quantizedRemaining(16) == 16 && AbstractCompanionTask.quantizedRemaining(17) == 32,
                "quantum boundaries round upward, never down");
    }

    /** 接近规划零确认进展：约三十活动秒内给出 planning_stall 终态，不再无限 running。 */
    private static void approachPlanningStallTerminatesBounded(Unsafe memory) throws Exception {
        try (Fixture f = new Fixture(memory)) {
            GoToThenDoTask<MoveToTaskRecord> task = f.approachTask();
            TaskState state = TaskState.RUNNING;
            int ticks = 0;
            while (state == TaskState.RUNNING && ticks++ < 900) {
                f.nextTick();
                state = task.tick(f.player);
            }
            check(state == TaskState.FAILED && ticks <= 700,
                    "approach planning with zero verified progress must terminate within the idle budget, ticks=" + ticks);
            TaskResult result = task.result(TaskState.FAILED);
            check(String.valueOf(result.data().get("failure_type")).equalsIgnoreCase("planning_stall"),
                    "approach stall must fail as planning_stall, not as a terrain verdict");
            check(result.message().contains("approach planning made no verified progress"),
                    "failure message must name the exhausted planning budget");
        }
    }

    /** 接近规划持续有确认进展：预算不断补满，同一个守卫不得把健康的长规划判死。 */
    private static void approachPlanningWithProgressKeepsRunning(Unsafe memory) throws Exception {
        try (Fixture f = new Fixture(memory)) {
            GoToThenDoTask<MoveToTaskRecord> task = f.approachTask();
            for (int tick = 0; tick < 1200; tick++) {
                f.nextTick();
                if (tick % 100 == 0) f.session.verifiedProgressTick = f.player.level().getGameTime();
                check(task.tick(f.player) == TaskState.RUNNING,
                        "planning that keeps confirming progress must stay running at tick " + tick);
            }
        }
    }

    /** 接近阶段的记分牌：planning/moving 阶段、量化剩余与 calc 心跳都要在 progress() 里可见。 */
    private static void approachProgressEmitsScoreboardHeartbeat(Unsafe memory) throws Exception {
        try (Fixture f = new Fixture(memory)) {
            GoToThenDoTask<MoveToTaskRecord> task = f.approachTask();
            f.nextTick();
            Map<String, Object> progress = task.progress();
            check("planning".equals(progress.get("phase")), "planning navigator must report the planning phase");
            check(Integer.valueOf(16).equals(progress.get("remaining")),
                    "approach distance must keep a nonzero quantized remaining");
            check(progress.get("initial") instanceof Integer, "the initial scale must be reported alongside remaining");
            Object before = progress.get("calc");
            f.session.verifiedProgressTick = f.player.level().getGameTime();
            bumpCalcAttempts(f, 3);
            progress = task.progress();
            check(progress.get("calc") instanceof Number calc && calc.longValue() > 0 && !calc.equals(before),
                    "calc attempts must grow during planning so the gate keeps emitting heartbeat events");
        }
    }

    /** 移动任务同一契约：长规划停滞期 progress() 必须携带单调增长的 calc 心跳。 */
    private static void moveToPlanningHeartbeat(Unsafe memory) throws Exception {
        try (Fixture f = new Fixture(memory)) {
            MoveToCompanionTask task = new MoveToCompanionTask(f.player,
                    new MoveToTaskRecord("heartbeat", 600, 8D, 0D, 0D, null, false));
            field(AbstractCompanionTask.class, "nav").set(task, f.nav);
            Map<String, Object> progress = task.progress();
            check("planning".equals(progress.get("phase")), "move planning must report the planning phase");
            check(progress.get("calc") instanceof Number, "move planning progress must carry the calc heartbeat");
            bumpCalcAttempts(f, 5);
            Object before = progress.get("calc");
            progress = task.progress();
            check(progress.get("calc") instanceof Number calc && !calc.equals(before),
                    "move calc heartbeat must be monotonic across restarts");
        }
    }

    private static void bumpCalcAttempts(Fixture f, int count) throws Exception {
        var ground = (EmbeddedBaritoneNavigator) field(TransportNavigator.class, "ground").get(f.navigator);
        @SuppressWarnings("unchecked")
        EnumMap<PathEvent, Integer> events =
                (EnumMap<PathEvent, Integer>) field(EmbeddedBaritoneNavigator.class, "events").get(ground);
        events.put(PathEvent.CALC_STARTED, events.getOrDefault(PathEvent.CALC_STARTED, 0) + count);
    }

    private static final class Fixture implements AutoCloseable {
        final ClientActorBoundary actor = ClientRuntime.actor();
        final Map<Field, Object> saved = new java.util.LinkedHashMap<>();
        final Minecraft minecraft;
        final TestPlayer player;
        final FlatLevel world;
        final DefaultBodyControlPort body = new DefaultBodyControlPort();
        LocalPlayerContext context;
        PlayerNav nav;
        TransportNavigator navigator;
        Session session;
        long tick;

        Fixture(Unsafe memory) throws Exception {
            minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
            player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
            var inventoryMenu = (InventoryMenu) memory.allocateInstance(InventoryMenu.class);
            inventoryMenu.setCarried(ItemStack.EMPTY);
            field(LocalPlayer.class, "inventoryMenu").set(player, inventoryMenu); player.containerMenu = inventoryMenu;
            world = (FlatLevel) memory.allocateInstance(FlatLevel.class);
            minecraft.player = player; minecraft.level = world;
            field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());
            field(Level.class, "dimension").set(world, Level.OVERWORLD);
            field(LocalPlayer.class, "clientLevel").set(player, world);
            field(LocalPlayer.class, "level").set(player, world);
            field(LocalPlayer.class, "position").set(player, new Vec3(.5, 0, .5));
            field(LocalPlayer.class, "blockPosition").set(player, BlockPos.containing(.5, 0, .5));
            field(LocalPlayer.class, "onGround").setBoolean(player, true);
            player.input = new Input();
            replace(field(Minecraft.class, "instance"), null, minecraft);
            replace(field(ClientActorBoundary.class, "minecraft"), actor, minecraft);
            replace(field(ClientActorBoundary.class, "body"), actor, body);
            replace(field(ClientActorBoundary.class, "observedPlayer"), actor, player);
            remember(field(ClientActorBoundary.class, "activeContext"), actor);
            remember(field(ClientActorBoundary.class, "tickRevision"), actor);
            invoke(body, "requestAutomation", LocalPlayer.class, player);
            invoke(body, "fulfillAutomationRequest", LocalPlayer.class, player);
            nav = PlayerNav.to(player, () -> new GoalCompiler.Compiled(
                    new MoveToTaskRecord("guard", 600, 8D, 0D, 0D, null, false).coordinateGoal(),
                    LongSets.emptySet()), 1, () -> false).withTransportMode(TransportMode.GROUND);
            navigator = (TransportNavigator) field(PlayerNav.class, "navigator").get(nav);
            bareGround(memory);
            session = new Session(TransportSession.Result.running("planning"));
            field(TransportNavigator.class, "session").set(navigator, session);
            field(TransportNavigator.class, "activeDestination").set(navigator, BlockPos.ZERO);
            nextTick();
        }

        GoToThenDoTask<MoveToTaskRecord> approachTask() {
            var record = new MoveToTaskRecord("guard", 6000, 8D, 0D, 0D, null, false);
            var task = new GoToThenDoTask<MoveToTaskRecord>(player, record) {
                @Override protected PlayerNav buildNav() { return Fixture.this.nav; }
                @Override protected boolean reached() { return false; }
                @Override protected TaskState act() { throw new AssertionError("act must not run before arrival"); }
                @Override protected BlockPos gotoFirstTarget() { return new BlockPos(8, 0, 0); }
                @Override protected String successMessage() { return "unused"; }
            };
            try { task.start(player); } catch (Exception failure) { throw new RuntimeException(failure); }
            return task;
        }

        /** 空转的地面导航桩：只提供事件计数与零进展，不启动真实搜索。 */
        private void bareGround(Unsafe memory) throws Exception {
            var ground = (EmbeddedBaritoneNavigator) memory.allocateInstance(EmbeddedBaritoneNavigator.class);
            field(EmbeddedBaritoneNavigator.class, "player").set(ground, player);
            field(EmbeddedBaritoneNavigator.class, "playerWorld").set(ground, world);
            field(EmbeddedBaritoneNavigator.class, "progress").set(ground, memory.allocateInstance(
                    Class.forName("org.maiwithu.maicraft.core.pathing.baritone.NavigationProgress")));
            field(EmbeddedBaritoneNavigator.class, "events").set(ground, new EnumMap<>(PathEvent.class));
            field(EmbeddedBaritoneNavigator.class, "ledger").set(ground, new TerrainBill());
            for (String diagnostics : List.of("dispatchEvidence", "healthEvidence", "dispatchRecovery",
                    "probeRecovery", "failureEvidence")) {
                field(EmbeddedBaritoneNavigator.class, diagnostics).set(ground, Map.of());
            }
            field(EmbeddedBaritoneNavigator.class, "probeProgress").set(ground, new PlanningWorkProgress());
            field(TransportNavigator.class, "ground").set(navigator, ground);
        }

        void nextTick() throws Exception {
            world.time = ++tick;
            field(ClientActorBoundary.class, "tickRevision").setLong(actor, tick);
            var ctor = DefaultLocalPlayerContext.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            context = (LocalPlayerContext) ctor.newInstance(actor, minecraft, player, world, null, null,
                    field(ClientActorBoundary.class, "bodyEpoch").getLong(actor),
                    field(ClientActorBoundary.class, "controlRevision").getLong(actor), tick, true);
            field(ClientActorBoundary.class, "activeContext").set(actor, context);
            invoke(body, "beginTick", long.class, tick);
        }

        private void remember(Field f, Object owner) throws Exception { saved.put(f, f.get(owner)); }
        private void replace(Field f, Object owner, Object value) throws Exception {
            remember(f, owner); f.set(owner, value);
        }
        public void close() throws Exception {
            TransportRuntime.abandon();
            for (var entry : saved.entrySet()) entry.getKey().set(
                    entry.getKey().getDeclaringClass() == Minecraft.class ? null : actor, entry.getValue());
        }
    }

    private static final class Session implements TransportSession {
        Result result;
        int ticks, stops;
        long verifiedProgressTick = Long.MIN_VALUE;
        Session(Result result) { this.result = result; }
        public Result tick(LocalPlayerContext context) { ticks++; return result; }
        public void requestStop() { stops++; }
        public void abandon() { }
        public boolean safeToInterrupt() { return result.terminal(); }
        public boolean livenessActive() { return true; }
        public long lastVerifiedProgressTick() { return verifiedProgressTick; }
        public String phase() { return result.code(); }
        public Map<String, Object> diagnostics() { return Map.of("ticks", ticks); }
    }

    private static final class FlatLevel extends ClientLevel {
        long time;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public long getGameTime() { return time; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return (pos.getY() < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
    }

    private static final class TestPlayer extends LocalPlayer {
        @Override public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) { return ItemStack.EMPTY; }
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isSleeping() { return false; }
        @Override public float getHealth() { return 20; }
        @Override public float getAbsorptionAmount() { return 0; }
        @Override public void setSprinting(boolean sprinting) { }
    }

    private static void invoke(Object owner, String name, Class<?> parameter, Object value) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, parameter); method.setAccessible(true); method.invoke(owner, value);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
