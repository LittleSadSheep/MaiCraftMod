package org.maiwithu.maicraft.core.integration.create.elevator;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import sun.misc.Unsafe;

/** Production confirmation and terminal guards, using real receipt objects and immutable observations. */
public final class ElevatorSessionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var memory = (Unsafe) unsafeField.get(null);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        set(Entity.class, player, "position", new Vec3(0.5, 100, 0.5));
        var cabin = new CreateElevatorBridge.Cabin(player, null, new CreateElevatorBridge.Column(0, 0, Direction.NORTH),
                2, 102, true, new Vec3(0, 100, 0), Map.of(), null, List.of(),
                List.of(new CreateElevatorBridge.Floor(102, "1", "Landing")), 0, 200);
        check(cabin.aligned(102), "actual contact offset alignment");
        set(Entity.class, player, "position", new Vec3(0.5, 100.4, 0.5));
        check(!cabin.aligned(102), "Create's coarse arrived flag alone authorized exit");
        check(!cabin.serves(103), "arbitrary unregistered floor was accepted");
        check(Math.abs(cabin.originAt(114).y - 111.6) < 1e-6, "moving entity position was mistaken for contact floor Y");
        geometryStateHash(); receiptGate(); failedWalkAndCancelledExit(player); terminalGuard(player);
        System.out.println("ElevatorSessionTest: passed");
    }

    private static void geometryStateHash() {
        var nbt = new net.minecraft.nbt.CompoundTag();
        var blocks = new java.util.HashMap<BlockPos, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo>();
        var pos = BlockPos.ZERO;
        blocks.put(pos, new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(pos,
                net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(), nbt));
        int before = CreateElevatorTravel.geometryHash(blocks);
        nbt.putString("floor_label", "different display data");
        check(before == CreateElevatorTravel.geometryHash(blocks), "display NBT unnecessarily invalidated cabin geometry");
        blocks.put(pos, new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(pos,
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), nbt));
        check(before != CreateElevatorTravel.geometryHash(blocks), "actual collision state change failed to invalidate geometry");
    }

    private static void receiptGate() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        NativeActionReceipt[] current = new NativeActionReceipt[1];
        LocalPlayerContext[] context = new LocalPlayerContext[1];
        var constructor = NativeActionReceipt.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        NativeActionPort port = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(), new Class<?>[]{NativeActionPort.class}, (p,m,a) -> {
            if (m.getName().equals("poll")) return a[1];
            if (!m.getName().equals("submitControlProtocol")) throw new AssertionError("unexpected native path: " + m.getName());
            ((Runnable) a[2]).run();
            return current[0] = (NativeActionReceipt) constructor.newInstance(NativeActionReceipt.Kind.MOD_PROTOCOL,
                    context[0], 40, 2, a[3], null, null);
        });
        context[0] = proxyContext(null, null, port);
        ElevatorActions actions = new ElevatorActions();
        check(actions.submit(context[0], "test:select", sends::incrementAndGet, NativeConfirmation.pending()), "first gesture not submitted");
        check(!actions.submit(context[0], "test:duplicate", sends::incrementAndGet, NativeConfirmation.pending()) && sends.get() == 1,
                "a pending gesture was repeated without server evidence");
        check(!actions.settle(context[0]), "pending receipt was mistaken for completed selection");
        var finish = NativeActionReceipt.class.getDeclaredMethod("finish", NativeActionReceipt.Status.class, String.class); finish.setAccessible(true);
        finish.invoke(current[0], NativeActionReceipt.Status.CONFIRMED_APPLIED, "synchronized target");
        check(actions.settle(context[0]) && !actions.pending(), "confirmed gesture did not release the serial slot");
        check(actions.submit(context[0], "test:click", sends::incrementAndGet, NativeConfirmation.pending()), "next gesture unavailable");
        finish.invoke(current[0], NativeActionReceipt.Status.UNCERTAIN, "server outcome missing");
        check(actions.settle(context[0]) && actions.uncertain && actions.failure != null, "uncertain gesture became successful transport");
        actions.abandon(); check(sends.get() == 2, "abandon emitted another world operation");
    }

    private static void failedWalkAndCancelledExit(LocalPlayer player) throws Exception {
        var floor = net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState();
        var first = BlockPos.ZERO;
        var second = new BlockPos(3, 0, 0);
        var blocks = Map.of(first, new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(first, floor, null),
                second, new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(second, floor, null));
        var geometry = new ElevatorGeometry(blocks, null, 0.6, 1.8);
        Vec3 start = new Vec3(0.5, 1, 0.5), destination = new Vec3(3.5, 1, 0.5);
        set(Entity.class, player, "position", start);
        var cabin = new CreateElevatorBridge.Cabin(player, null, new CreateElevatorBridge.Column(0, 0, Direction.NORTH),
                0, 1, true, Vec3.ZERO, blocks, null, List.of(), List.of(), 0, 200);
        BodyControlPort body = (BodyControlPort) Proxy.newProxyInstance(BodyControlPort.class.getClassLoader(), new Class<?>[]{BodyControlPort.class},
                (p,m,a) -> m.getReturnType() == boolean.class ? true : null);
        LocalPlayerContext context = proxyContext(player, body, null);
        ElevatorMotion motion = new ElevatorMotion();
        // This is the persisted state after a failed path search: a goal, but no route.
        set(ElevatorMotion.class, motion, "localGoal", destination);
        for (int tick = 0; tick < 2; tick++) check(motion.inside(context, cabin, geometry, destination,
                it.unimi.dsi.fastutil.longs.LongSets.emptySet()) == ElevatorMotion.Progress.BLOCKED, "empty failed route became arrival on the next tick");
        set(Entity.class, player, "position", destination);
        check(motion.inside(context, cabin, geometry, destination, it.unimi.dsi.fastutil.longs.LongSets.emptySet())
                == ElevatorMotion.Progress.REACHED, "actual supported destination was rejected");
        set(Entity.class, player, "position", start);
        CreateElevatorTravel journey = new CreateElevatorTravel(new BlockPos(4, 1, 0)); journey.requestStop();
        set(CreateElevatorTravel.class, journey, "geometry", geometry);
        set(CreateElevatorTravel.class, journey, "exit", new ElevatorGeometry.Landing(destination.add(1, 0, 0), destination));
        set(CreateElevatorTravel.class, journey, "exitFloor", 1);
        set(CreateElevatorTravel.class, journey, "aboard", true);
        Field field = CreateElevatorTravel.class.getDeclaredField("motion"); field.setAccessible(true);
        set(ElevatorMotion.class, field.get(journey), "localGoal", destination);
        var exit = CreateElevatorTravel.class.getDeclaredMethod("exit", LocalPlayerContext.class, CreateElevatorBridge.Cabin.class, boolean.class); exit.setAccessible(true);
        for (long tick : new long[]{0, 19, 20}) {
            set(CreateElevatorTravel.class, journey, "now", tick);
            var result = (TransportSession.Result) exit.invoke(journey, context, cabin, true);
            check((result.state() == TransportSession.State.FAILED) == (tick == 20), "blocked cancellation ignored the bounded door synchronization window");
            if (tick == 20) check(result.uncertain() && result.code().equals("elevator_needs_attention") && !journey.safeToInterrupt(),
                    "blocked cabin exit claimed safe disembarkation");
        }
    }

    private static void terminalGuard(LocalPlayer player) throws Exception {
        BodyControlPort body = (BodyControlPort) Proxy.newProxyInstance(BodyControlPort.class.getClassLoader(), new Class<?>[]{BodyControlPort.class},
                (p,m,a) -> m.getReturnType() == boolean.class ? true : null);
        LocalPlayerContext context = proxyContext(player, body, null);
        var exit = CreateElevatorTravel.class.getDeclaredMethod("exit", LocalPlayerContext.class, CreateElevatorBridge.Cabin.class, boolean.class); exit.setAccessible(true);
        var stop = CreateElevatorTravel.class.getDeclaredMethod("stopSafely", LocalPlayerContext.class, CreateElevatorBridge.Cabin.class); stop.setAccessible(true);
        CreateElevatorTravel riding = new CreateElevatorTravel(new BlockPos(0, 112, 0)); riding.requestStop();
        check(((TransportSession.Result) stop.invoke(riding, context, null)).state() == TransportSession.State.RUNNING
                        && !riding.safeToInterrupt(), "cancel abandoned the body before a safe fixed landing");
        for (int y : new int[]{112, 100}) {
            CreateElevatorTravel journey = new CreateElevatorTravel(new BlockPos(0, 112, 0));
            set(CreateElevatorTravel.class, journey, "safe", true);
            set(CreateElevatorTravel.class, journey, "exit", new ElevatorGeometry.Landing(new Vec3(0.5, 112, 0.5), Vec3.ZERO));
            set(Entity.class, player, "position", new Vec3(0.5, y, 0.5));
            var result = (TransportSession.Result) exit.invoke(journey, context, null, false);
            check((result.state() == TransportSession.State.SUCCEEDED) == (y == 112), "fixed floor at the wrong height became success");
        }
        riding.abandon();
    }

    private static LocalPlayerContext proxyContext(LocalPlayer player, BodyControlPort body, NativeActionPort actions) {
        return (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class}, (p,m,a) -> switch (m.getName()) {
            case "player" -> player; case "body" -> body; case "actions" -> actions;
            case "mutationAvailable", "isCurrent", "permitsNativeActions" -> true;
            case "bodyEpoch", "controlRevision", "tickRevision" -> 1L;
            default -> null;
        });
    }
    private static void set(Class<?> type, Object instance, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
