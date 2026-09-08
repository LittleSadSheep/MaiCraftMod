// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
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
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportNavigator;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;

/** Real MoveTo/nav/runtime ordering with an inert body: no game, window, path search or packets. */
public final class MoveToTransportCompletionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        for (String phase : List.of("elevator_ride", "elevator_walk_exit", "jetpack_restore")) {
            nativeCompletion(memory, phase);
        }
        transportFailure(memory, false, .5);
        transportFailure(memory, true, .5);
        transportFailure(memory, false, 8.5);
        discoveryFailure(memory);
        ordinaryNearArrival(memory);
        automaticLandingResult(memory);
        observationDoesNotCompleteTravel(memory);
        System.out.println("MoveToTransportCompletionTest: passed");
    }

    private static void observationDoesNotCompleteTravel(Unsafe memory) throws Exception {
        try(var f=new Fixture(memory,.5)) {
            var goal=new org.maiwithu.maicraft.intent.Goal("maicraft:travel","Choose an elevator floor",null,
                    "{\"transport_mode\":\"elevator\"}","{}",List.of(),List.of());
            var record=new org.maiwithu.maicraft.intent.IntentTaskRecord(java.util.UUID.randomUUID(),null,goal);
            record.setState(TaskState.RUNNING);
            Class<?> type=Class.forName("org.maiwithu.maicraft.intent.IntentTask");
            var ctor=type.getDeclaredConstructor(LocalPlayer.class,record.getClass(),org.maiwithu.maicraft.intent.IntentRuntime.class);
            ctor.setAccessible(true); Object parent=ctor.newInstance(f.player,record,null);
            var observed=new org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloorTaskRecord(
                    "test-floor-sync",100,java.util.UUID.randomUUID(),null,true);
            observed.setState(TaskState.SUCCESS);
            org.maiwithu.maicraft.task.Task child=new org.maiwithu.maicraft.task.Task() {
                public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
                public void stop(LocalPlayer player,StopReason reason) {}
                public String name() { return "native floor observation"; }
                public org.maiwithu.maicraft.task.TaskResult result(TaskState state) { return org.maiwithu.maicraft.task.TaskResult.ok("floors synchronized"); }
            };
            field(type,"child").set(parent,child); field(type,"childRecord").set(parent,observed);
            field(type,"reobserveAfterChild").setBoolean(parent,true);
            var finish=type.getDeclaredMethod("finishChild"); finish.setAccessible(true);
            check(finish.invoke(parent)==TaskState.RUNNING && record.stepIndex()==0 && record.getState()==TaskState.RUNNING,
                    "successful floor observation must leave the semantic travel step unfinished for its LLM decision");
            check(field(type,"childRecord").get(parent)==null && !field(type,"reobserveAfterChild").getBoolean(parent),
                    "observation cleanup releases the child without leaking its continuation into the later ride");
        }
    }

    private static void nativeCompletion(Unsafe memory, String phase) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = coordinates(0D, 0D);
            var task = f.task(record, true);
            var session = f.transport(TransportSession.Result.running(phase));
            check(task.onTick() == TaskState.RUNNING && session.ticks == 1,
                    phase + ": satisfied body coordinates must not skip the native drive");
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING && session.ticks == 2 && session.stops == 0,
                    phase + ": still settling must not cancel or complete the task");
            check(record.internalVerifiedPosition() == null, "unfinished transport cannot issue an arrival receipt");
            session.result = TransportSession.Result.success("native exit/restoration complete");
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING && session.ticks == 3 && !TransportRuntime.occupied(),
                    "native completion must be consumed before its next-tick ground handoff");
            f.nextTick();
            check(task.onTick() == TaskState.SUCCESS && record.internalVerifiedPosition() != null,
                    "settled native transport must permit the real supported arrival");
            check(task.result(TaskState.SUCCESS).success() && session.stops == 0 && f.callbacks == 1,
                    "successful task cleanup must not relabel the completed transport as cancelled");
            check(((Map<?, ?>) TransportRuntime.diagnosticState().get("last_transport")).get("state").equals("succeeded"),
                    "last transport must retain its native success");
        }
    }

    private static void transportFailure(Unsafe memory, boolean uncertain, double x) throws Exception {
        try (var f = new Fixture(memory, x)) {
            var record = coordinates(0D, null);
            var task = f.task(record, x < 1);
            var session = f.transport(TransportSession.Result.failed("elevator_needs_attention", "still aboard",
                    !uncertain, uncertain));
            check(task.onTick() == TaskState.FAILED && session.ticks == 1 && f.nav.failType() == FailureType.UNKNOWN,
                    "effectful or uncertain transport failure must remain failed even near the destination");
            check(record.internalVerifiedPosition() == null && !field(MoveToCompanionTask.class, "nearRetried").getBoolean(task),
                    "transport failure must neither create an arrival receipt nor start a near retry");
            check(!task.result(TaskState.FAILED).success(), "cleanup must preserve the failed task result");
        }
    }

    @SuppressWarnings("unchecked")
    private static void discoveryFailure(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = new MoveToTaskRecord("find", 600, null, null, null, "minecraft:stone", false);
            var task = f.task(record, false);
            var finder = new NearestBlockFinder(f.player, Blocks.STONE);
            var candidates = (List<BlockPos>) field(NearestBlockFinder.class, "candidates").get(finder);
            candidates.addAll(List.of(new BlockPos(2, 0, 0), new BlockPos(4, 0, 0)));
            field(MoveToCompanionTask.class, "finder").set(task, finder);
            f.transport(TransportSession.Result.failed("transport_uncertain", "inspect first", true, true));
            check(task.onTick() == TaskState.FAILED && candidates.size() == 2,
                    "uncertain transport must not rotate FIND candidates into another attempt");
            task.result(TaskState.FAILED);
        }
    }

    private static void ordinaryNearArrival(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, 2.5)) {
            var record = coordinates(0D, null);
            var task = f.task(record, false);
            Object ground = field(TransportNavigator.class, "ground").get(f.navigator);
            field(ground.getClass(), "terminalFailure").setBoolean(ground, true);
            field(ground.getClass(), "failureType").set(ground, FailureType.NO_PATH);
            field(ground.getClass(), "failureReason").set(ground, "ordinary terrain ended here");
            check(task.onTick() == TaskState.SUCCESS && record.internalVerifiedPosition() != null,
                    "ordinary NO_PATH within the existing three-block tolerance must retain near success");
            task.result(TaskState.SUCCESS);
        }
    }

    private static MoveToTaskRecord coordinates(Double x, Double y) {
        return new MoveToTaskRecord("move", 600, x, y, 0D, null, false);
    }

    private static final class Session implements TransportSession {
        Result result;
        int ticks, stops;
        Session(Result result) { this.result = result; }
        public Result tick(LocalPlayerContext context) { ticks++; return result; }
        public void requestStop() { stops++; }
        public void abandon() { }
        public boolean safeToInterrupt() { return result.terminal(); }
        public boolean livenessActive() { return true; }
        public String phase() { return result.code(); }
        public Map<String, Object> diagnostics() { return Map.of("ticks", ticks); }
    }

    private static void automaticLandingResult(Unsafe memory) throws Exception {
        var failed = Map.<String,Object>of("strategy","WATER","complete",true,"failed",true,"native_water_contact",false);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.report(failed);
        try (var f = new Fixture(memory,.5)) {
            var task = f.task(coordinates(0D,0D),true); task.onStart();
            check(task.onTick()==TaskState.SUCCESS,"an old landing failure must not contaminate a new move");
            check(!task.result(TaskState.SUCCESS).data().containsKey("landing_assist"),"old diagnostics must not be attributed to this task");
        }
        try (var f = new Fixture(memory,.5)) {
            var record=coordinates(0D,0D); var task=f.task(record,true); task.onStart();
            org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.report(failed);
            check(task.onTick()==TaskState.FAILED && record.internalVerifiedPosition()==null,
                    "arrival cannot overwrite this move's failed automatic protection");
            check(task.result(TaskState.FAILED).data().get("landing_assist").equals(failed),"failure receipt must retain actual rescue evidence");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClientActorBoundary actor = ClientRuntime.actor();
        final Map<Field, Object> saved = new LinkedHashMap<>();
        final Minecraft minecraft;
        final TestPlayer player;
        final FlatLevel world;
        final DefaultBodyControlPort body = new DefaultBodyControlPort();
        LocalPlayerContext context;
        PlayerNav nav;
        TransportNavigator navigator;
        GoalCompiler.Compiled compiled = GoalCompiler.standOn(BlockPos.ZERO);
        long tick;
        int callbacks;

        Fixture(Unsafe memory, double x) throws Exception {
            minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
            player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
            world = (FlatLevel) memory.allocateInstance(FlatLevel.class);
            minecraft.player = player; minecraft.level = world;
            field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());
            field(Level.class, "dimension").set(world, Level.OVERWORLD);
            field(LocalPlayer.class, "clientLevel").set(player, world);
            field(LocalPlayer.class, "level").set(player, world);
            field(LocalPlayer.class, "position").set(player, new Vec3(x, 0, .5));
            field(LocalPlayer.class, "blockPosition").set(player, BlockPos.containing(x, 0, .5));
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
            nextTick();
        }

        void nextTick() throws Exception {
            world.time = ++tick;
            field(ClientActorBoundary.class, "tickRevision").setLong(actor, tick);
            Constructor<?> ctor = DefaultLocalPlayerContext.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            context = (LocalPlayerContext) ctor.newInstance(actor, minecraft, player, world, null, null,
                    field(ClientActorBoundary.class, "bodyEpoch").getLong(actor),
                    field(ClientActorBoundary.class, "controlRevision").getLong(actor), tick, true);
            field(ClientActorBoundary.class, "activeContext").set(actor, context);
            invoke(body, "beginTick", long.class, tick);
        }

        MoveToCompanionTask task(MoveToTaskRecord record, boolean reached) throws Exception {
            if (record.kind != MoveToTaskRecord.Kind.FIND) compiled = new GoalCompiler.Compiled(record.coordinateGoal(),
                    it.unimi.dsi.fastutil.longs.LongSets.emptySet());
            var task = new MoveToCompanionTask(player, record);
            nav = PlayerNav.to(player, () -> compiled, 1, () -> reached).withTransportMode(TransportMode.GROUND);
            navigator = (TransportNavigator) field(PlayerNav.class, "navigator").get(nav);
            field(AbstractCompanionTask.class, "nav").set(task, nav);
            return task;
        }

        Session transport(TransportSession.Result result) throws Exception {
            var session = new Session(result);
            field(TransportNavigator.class, "session").set(navigator, session);
            field(TransportNavigator.class, "activeDestination").set(navigator, BlockPos.ZERO);
            field(TransportNavigator.class, "targetFingerprint").set(navigator, compiled.semanticFingerprint());
            field(TransportNavigator.class, "legOrigin").set(navigator, new Vec3(-5, 0, .5));
            Constructor<?> ctor = Class.forName(TransportNavigator.class.getPackageName() + ".TransportPlan$Offer").getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object offer = ctor.newInstance("elevator", BlockPos.ZERO, 20D, (Supplier<TransportSession>) () -> session);
            field(TransportNavigator.class, "offers").set(navigator, List.of(offer));
            field(TransportNavigator.class, "offerIndex").setInt(navigator, 1);
            check(TransportRuntime.acquire(navigator, "elevator", session, context, completed -> {
                callbacks++;
                try { field(TransportNavigator.class, "transportResult").set(navigator, completed); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }), "fixture transport must acquire the real owner lease");
            return session;
        }

        private void remember(Field field, Object owner) throws Exception { saved.put(field, field.get(owner)); }
        private void replace(Field field, Object owner, Object value) throws Exception {
            remember(field, owner); field.set(owner, value);
        }
        public void close() throws Exception {
            TransportRuntime.abandon();
            for (var entry : saved.entrySet()) entry.getKey().set(
                    entry.getKey().getDeclaringClass() == Minecraft.class ? null : actor, entry.getValue());
        }
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
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isAlive() { return true; }
        @Override public float getHealth() { return 20; }
        @Override public float getAbsorptionAmount() { return 0; }
        @Override public void setSprinting(boolean sprinting) { }
    }

    private static void invoke(Object owner, String name, Class<?> parameter, Object value) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, parameter); method.setAccessible(true); method.invoke(owner, value);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
