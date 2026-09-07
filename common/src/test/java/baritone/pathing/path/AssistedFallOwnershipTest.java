package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.InputOverrideHandler;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.SubmergedWaterTravelPolicy;
import org.maiwithu.maicraft.core.pathing.baritone.SwimTravelControl;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistSession;
import sun.misc.Unsafe;

/** Drive the real executor through relocation, stopping before unrelated terrain calculation. */
public final class AssistedFallOwnershipTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
        var world = (TestLevel) memory.allocateInstance(TestLevel.class);
        field(LocalPlayer.class, "level").set(player, world);
        field(LocalPlayer.class, "position").set(player, new Vec3(2.5, 0, 0.5));
        field(LocalPlayer.class, "deltaMovement").set(player, Vec3.ZERO);
        field(LocalPlayer.class, "eyeHeight").setFloat(player, 1.62F);
        field(LocalPlayer.class, "onGround").setBoolean(player, true);
        BetterBlockPos[] feet = {new BetterBlockPos(2, 0, 0)};
        IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "player" -> player; case "playerFeet" -> feet[0]; case "world" -> world;
                    default -> throw new AssertionError(method.getName());
                });
        var fall = (MovementFall) memory.allocateInstance(MovementFall.class);
        var previous = (MovementFall) memory.allocateInstance(MovementFall.class);
        var source = new BetterBlockPos(0, 12, 0); var destination = new BetterBlockPos(1, 0, 0);
        field(Movement.class, "ctx").set(fall, context);
        field(Movement.class, "src").set(fall, source); field(Movement.class, "dest").set(fall, destination);
        field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.RUNNING));
        field(Movement.class, "validPositionsCached").set(previous, Set.of(feet[0]));
        var session = new LandingAssistSession(new LandingAssistPlan(LandingAssistPlan.Kind.WATER,
                destination, destination, destination.below(), Direction.UP, false));
        field(MovementFall.class, "landingAssist").set(fall, session);
        IPath path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "movements" -> List.of(previous, fall); case "length" -> 3;
                    case "positions" -> List.of(source, source, destination);
                    default -> throw new AssertionError(method.getName());
                });
        var backend = (Baritone) memory.allocateInstance(Baritone.class);
        var input = (InputOverrideHandler) memory.allocateInstance(InputOverrideHandler.class);
        field(InputOverrideHandler.class, "inputForceStateMap").set(input, new HashMap<>());
        field(Baritone.class, "inputOverrideHandler").set(backend, input);
        var behavior = (PathingBehavior) memory.allocateInstance(PathingBehavior.class);
        field(PathingBehavior.class, "baritone").set(behavior, backend);
        var executor = (PathExecutor) memory.allocateInstance(PathExecutor.class);
        field(PathExecutor.class, "ctx").set(executor, context); field(PathExecutor.class, "path").set(executor, path);
        field(PathExecutor.class, "behavior").set(executor, behavior); field(PathExecutor.class, "pathPosition").setInt(executor, 1);
        field(PathExecutor.class, "waterTravel").set(executor,
                new SubmergedWaterTravelPolicy(context, path, new SwimTravelControl.BodyState().bind(player, world)));
        var tick = PathExecutor.class.getDeclaredMethod("tickMovement"); tick.setAccessible(true);
        try {
            tick.invoke(executor);
            throw new AssertionError("active landing owner was relocated before terrain calculation");
        } catch (InvocationTargetException stopped) {
            check(stopped.getCause() instanceof TerrainBoundary, "unexpected fixture access: " + stopped.getCause());
        }
        check(executor.getPosition() == 1 && !executor.finished(), "off-path supported feet retain the current recovery owner");
        var extend = PathExecutor.class.getDeclaredMethod("overrideFall", MovementFall.class); extend.setAccessible(true);
        field(Movement.class, "src").set(fall, new BetterBlockPos(0, 3, 0));
        check(extend.invoke(executor, fall) == null, "straight-line fall extension cannot steal the chosen landing cell");
        field(Movement.class, "src").set(fall, source);

        // Following an actual airborne tick, even the original source must not trigger item
        // preparation or a new cost snapshot after this session has consumed its bucket.
        var prepare = MovementFall.class.getDeclaredMethod("prepared", MovementState.class); prepare.setAccessible(true);
        var state = new MovementState().setStatus(MovementStatus.WAITING);
        field(LocalPlayer.class, "onGround").setBoolean(player, false); feet[0] = source;
        check((Boolean) prepare.invoke(fall, state), "airborne fall retains its prepared session");
        field(LocalPlayer.class, "onGround").setBoolean(player, true); feet[0] = new BetterBlockPos(2, 0, 0);
        check((Boolean) prepare.invoke(fall, state), "neighboring support does not recalculate using the consumed bucket");
        feet[0] = source;
        check((Boolean) prepare.invoke(fall, state), "an observed departure cannot restart preparation after returning to its source");
        check(!fall.safeToCancel(), "returning to source cannot release an unsettled landing owner");

        feet[0] = new BetterBlockPos(2, 0, 0);
        // BaritoneAPI lazily reads settings and creates its provider on the first timeout.
        // Give that bootstrap an isolated file root; no Minecraft window/world is constructed.
        var settingsClient = (net.minecraft.client.Minecraft) memory.allocateInstance(net.minecraft.client.Minecraft.class);
        field(net.minecraft.client.Minecraft.class, "gameDirectory").set(settingsClient, new java.io.File("assisted-fall-settings-fixture"));
        field(net.minecraft.client.Minecraft.class, "gameThread").set(settingsClient, Thread.currentThread());
        Field clientInstance = field(net.minecraft.client.Minecraft.class, "instance");
        Object previousClient = clientInstance.get(null);
        clientInstance.set(null, settingsClient);
        try {
        field(InputOverrideHandler.class, "blockBreakHelper").set(input, memory.allocateInstance(baritone.utils.BlockBreakHelper.class));
        field(PathExecutor.class, "ticksOnCurrent").setInt(executor, 10000);
        field(PathExecutor.class, "currentMovementOriginalCostEstimate").set(executor, 1.0);
        var timeout = PathExecutor.class.getDeclaredMethod("cancelIfTimedOut", Movement.class); timeout.setAccessible(true);
        input.setInputForceState(baritone.api.utils.input.Input.MOVE_FORWARD, true);
        check(!(Boolean) timeout.invoke(executor, fall) && executor.getPosition() == 1 && !executor.failed()
                        && input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD),
                "generic timeout cannot clear inputs or discard the current native recovery owner");
        field(MovementFall.class, "landingAssist").set(fall, null);
        check((Boolean) timeout.invoke(executor, fall) && executor.failed() && executor.finished()
                        && !input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD),
                "unassisted movement retains the real timeout cancellation and input release");
        field(MovementFall.class, "landingAssist").set(fall, session);
        field(PathExecutor.class, "pathPosition").setInt(executor, 1); field(PathExecutor.class, "failed").setBoolean(executor, false);
        field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.SUCCESS));
        field(LandingAssistSession.class, "complete").setBoolean(session, true);
        check((Boolean) timeout.invoke(executor, fall) && executor.failed(), "safe handoff restores the original timeout behavior");
        field(PathExecutor.class, "pathPosition").setInt(executor, 1); field(PathExecutor.class, "failed").setBoolean(executor, false);

        // Exercise both real off-path cancellation branches: the accumulated 2-block warning
        // window and the immediate 3-block exit. Neither may discard the unsettled fall.
        field(Movement.class, "validPositionsCached").set(previous, Set.of(source));
        for (double x : new double[]{4.0, 20.5}) {
            field(LocalPlayer.class, "position").set(player, new Vec3(x, 0, 0.5)); feet[0] = new BetterBlockPos(BlockPos.containing(x, 0, 0.5));
            field(LandingAssistSession.class, "complete").setBoolean(session, false);
            field(LandingAssistSession.class, "failed").setBoolean(session, false);
            field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.RUNNING));
            field(PathExecutor.class, "pathPosition").setInt(executor, 1); field(PathExecutor.class, "failed").setBoolean(executor, false);
            field(PathExecutor.class, "ticksAway").setInt(executor, 10000);
            input.setInputForceState(baritone.api.utils.input.Input.MOVE_FORWARD, true);
            try { tick.invoke(executor); throw new AssertionError("unsettled distant landing owner was cancelled"); }
            catch (InvocationTargetException boundary) { check(boundary.getCause() instanceof TerrainBoundary, "unexpected off-path boundary: " + boundary.getCause()); }
            check(executor.getPosition() == 1 && !executor.failed() && input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD),
                    "large displacement preserves the real fall cursor and its steering");
            field(LandingAssistSession.class, "complete").setBoolean(session, true);
            field(LandingAssistSession.class, "failed").setBoolean(session, true);
            field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.UNREACHABLE));
            field(PathExecutor.class, "ticksAway").setInt(executor, x < 10 ? 10000 : 0);
            tick.invoke(executor);
            check(executor.failed() && executor.finished() && !input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD),
                    "safe failed landing restores the original off-path cancellation and key release");
        }

        field(LocalPlayer.class, "position").set(player, new Vec3(1.5, 0, 0.5)); feet[0] = destination;
        field(PathExecutor.class, "pathPosition").setInt(executor, 1); field(PathExecutor.class, "failed").setBoolean(executor, false);
        field(LandingAssistSession.class, "complete").setBoolean(session, false);
        field(LandingAssistSession.class, "failed").setBoolean(session, false);
        field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.RUNNING));
        input.setInputForceState(baritone.api.utils.input.Input.MOVE_FORWARD, true);
        check(!executor.snipsnapifpossible() && executor.getPosition() == 1
                        && input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD),
                "direct path snapping cannot skip recovery even on supported destination feet");
        field(LandingAssistSession.class, "complete").setBoolean(session, true);
        field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.SUCCESS));
        check(executor.snipsnapifpossible() && executor.getPosition() == 2
                        && !input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD), "settled snapping still advances and clears keys");

        IPath withNext = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "movements" -> List.of(fall, previous); case "length" -> 3;
                    default -> throw new AssertionError(method.getName());
                });
        field(PathExecutor.class, "path").set(executor, withNext); field(PathExecutor.class, "pathPosition").setInt(executor, 0);
        field(Movement.class, "dest").set(previous, destination.east());
        var unloaded = (UnloadedBlocks) memory.allocateInstance(UnloadedBlocks.class); backend.bsi = unloaded;
        var pause = PathExecutor.class.getDeclaredMethod("pauseAtUnloadedNext", Movement.class); pause.setAccessible(true);
        field(LandingAssistSession.class, "complete").setBoolean(session, false);
        field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.RUNNING));
        input.setInputForceState(baritone.api.utils.input.Input.MOVE_FORWARD, true);
        check(!(Boolean) pause.invoke(executor, fall) && executor.getPosition() == 0 && unloaded.reads == 0
                        && input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD), "next chunk cannot pause an owned fall's steering");
        field(LandingAssistSession.class, "complete").setBoolean(session, true);
        field(LandingAssistSession.class, "failed").setBoolean(session, true);
        field(LocalPlayer.class, "onGround").setBoolean(player, false);
        MovementState ended = fall.updateState(new MovementState().setStatus(MovementStatus.RUNNING));
        check(ended.getStatus() == MovementStatus.UNREACHABLE, "safe session failure in water/climbable support must exit without another onGround gate");
        field(Movement.class, "currentState").set(fall, ended);
        check((Boolean) pause.invoke(executor, fall) && unloaded.reads == 1
                        && !input.isInputForcedDown(baritone.api.utils.input.Input.MOVE_FORWARD), "unloaded-next pause resumes after the session's safe exit");

        field(PathExecutor.class, "path").set(executor, path); field(PathExecutor.class, "pathPosition").setInt(executor, 1);
        field(LocalPlayer.class, "onGround").setBoolean(player, true); field(LocalPlayer.class, "position").set(player, new Vec3(2.5, 0, 0.5));
        feet[0] = new BetterBlockPos(2, 0, 0); field(Movement.class, "validPositionsCached").set(previous, Set.of(feet[0]));
        field(Movement.class, "currentState").set(fall, new MovementState().setStatus(MovementStatus.SUCCESS));
        tick.invoke(executor);
        check(executor.getPosition() == 0, "settled movement restores ordinary backward relocation");
        } finally { clientInstance.set(null, previousClient); }
        System.out.println("AssistedFallOwnershipTest: passed");
    }
    private static final class TestPlayer extends LocalPlayer {
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        public boolean isEyeInFluid(TagKey<Fluid> tag) { return false; }
        public boolean isInWater() { return false; }
        public int getAirSupply() { return 300; } public int getMaxAirSupply() { return 300; }
    }
    private static final class TestLevel extends ClientLevel {
        private TestLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        public long getGameTime() { return 0; }
        public boolean hasChunkAt(BlockPos pos) { return false; }
        public WorldBorder getWorldBorder() { throw new TerrainBoundary(); }
    }
    private static final class TerrainBoundary extends RuntimeException { }
    private static final class UnloadedBlocks extends baritone.utils.BlockStateInterface {
        int reads;
        private UnloadedBlocks() { super(null); }
        public boolean worldContainsLoadedChunk(int x, int z) { reads++; return false; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
