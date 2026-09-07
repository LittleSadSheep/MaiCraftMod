package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.movements.MovementFall;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.DefaultLocalPlayerContext;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;

/** Grounded source drift cannot skip preparation; airborne PREPPING cannot delay self-rescue. */
public final class FallDeparturePreparationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var f = new WaterLandingReplayTest.Fixture(false);
        field(Minecraft.class, "gameThread").set(f.minecraft, Thread.currentThread());
        field(Minecraft.class, "gameDirectory").set(f.minecraft, new java.io.File("fall-departure-settings-fixture"));
        f.minecraft.player = f.player; f.minecraft.level = f.world;
        var instance = field(Minecraft.class, "instance"); Object oldClient = instance.get(null);
        instance.set(null, f.minecraft);
        var actor = ClientRuntime.actor();
        var saved = new LinkedHashMap<Field, Object>();
        try {
            save(saved, actor, "minecraft", f.minecraft);
            save(saved, actor, "observedPlayer", f.player);
            save(saved, actor, "tickRevision", 1L);
            var constructor = DefaultLocalPlayerContext.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var nativeContext = constructor.newInstance(actor, f.minecraft, f.player, f.world, null, null,
                    field(ClientActorBoundary.class, "bodyEpoch").getLong(actor),
                    field(ClientActorBoundary.class, "controlRevision").getLong(actor), 1L, true);
            save(saved, actor, "activeContext", nativeContext);
            groundedPreparation(f);
            airbornePrepping(f);
        } finally {
            for (var entry : saved.entrySet()) entry.getKey().set(actor, entry.getValue());
            instance.set(null, oldClient);
        }
        System.out.println("FallDeparturePreparationTest: passed");
    }

    private static void groundedPreparation(WaterLandingReplayTest.Fixture f) throws Exception {
        f.position(12, 0, true); f.player.setXRot(0);
        var movement = movement(f);
        field(MovementFall.class, "landingAssist").set(movement, f.session);
        var departure = MovementFall.class.getDeclaredMethod("groundedBeforeDeparture"); departure.setAccessible(true);
        check(!f.player.blockPosition().equals(movement.getSrc()) && (Boolean) departure.invoke(movement),
                "a grounded player outside the original source cell must still prepare before departure");
        check(f.session.prepareAlreadyHeld(f.context), "native carried item is ready for the aiming stage");
        var prepare = MovementFall.class.getDeclaredMethod("prepareLanding", MovementState.class, LocalPlayerContext.class);
        prepare.setAccessible(true);
        var state = new MovementState().setStatus(MovementStatus.PREPPING);
        check(!(Boolean) prepare.invoke(movement, state, f.context) && state.getTarget().hasToForceRotations()
                        && Boolean.TRUE.equals(state.getInputStates().get(Input.SNEAK)),
                "equipment alone cannot release the edge before actual camera alignment");
        f.player.setXRot(90);
        check((Boolean) prepare.invoke(movement, state, f.context)
                        && Boolean.FALSE.equals(state.getInputStates().get(Input.SNEAK)) && f.uses == 0,
                "aligned ready preparation may depart without pouring water in place");
        f.position(11.9, -0.1, false);
        check(!(Boolean) departure.invoke(movement), "an actual airborne sample records departure");
        f.position(12, 0, true);
        check(!(Boolean) departure.invoke(movement), "returning to support cannot restart preparation over cleanup");
    }

    private static void airbornePrepping(WaterLandingReplayTest.Fixture f) throws Exception {
        f.position(24, -0.08, false); f.player.setXRot(0);
        f.player.inventory.setItem(0,net.minecraft.world.item.ItemStack.EMPTY);
        var movement = movement(f);
        var blocked = new BetterBlockPos(1, 23, 0);
        f.world.scene.blocks.put(blocked, Blocks.STONE.defaultBlockState());
        field(Movement.class, "positionsToBreak").set(movement, new BetterBlockPos[]{blocked});
        var state = movement.updateState(new MovementState().setStatus(MovementStatus.PREPPING));
        check(state.getStatus() == MovementStatus.RUNNING && movement.landingAssist() != null
                        && state.getTarget().hasToForceRotations(),
                "first airborne PREPPING tick adopts protection and aims instead of waiting to mine the source column");
        check(f.uses == 0, "planning the emergency does not bypass the real action reach window");
        int[] starts = {0}, supplyTicks = {0};
        var transaction = (Ae2ResourceSupply.Session)Proxy.newProxyInstance(
                Ae2ResourceSupply.Session.class.getClassLoader(),new Class<?>[]{Ae2ResourceSupply.Session.class},
                (proxy,method,args) -> switch (method.getName()) {
                    case "tick" -> { supplyTicks[0]++; yield java.util.Optional.empty(); }
                    case "phase" -> "opening_wireless_terminal";
                    default -> throw new AssertionError(method.getName());
                });
        var supply = new LandingMaterialSupply(java.util.List.of(net.minecraft.resources.ResourceLocation.parse("minecraft:water_bucket")),
                (player,request) -> { starts[0]++; return transaction; });
        field(LandingAssistSession.class,"materialSupply").set(movement.landingAssist(),supply);
        movement.landingAssist().tick(f.context);
        check(starts[0] == 1 && supplyTicks[0] == 1,
                "the first dangerous descending tick starts AE access instead of waiting until the ground is in reach");
    }

    private static MovementFall movement(WaterLandingReplayTest.Fixture f) throws Exception {
        var movement = (MovementFall) f.memory.allocateInstance(MovementFall.class);
        field(Movement.class, "src").set(movement, new BetterBlockPos(1, 24, 0));
        field(Movement.class, "dest").set(movement, new BetterBlockPos(BlockPos.ZERO));
        field(Movement.class, "ctx").set(movement, Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "player" -> f.player; case "world" -> f.world;
                    case "playerFeet" -> new BetterBlockPos(f.player.blockPosition());
                    case "playerHead" -> f.player.getEyePosition();
                    case "playerRotations" -> new Rotation(f.player.getYRot(), f.player.getXRot());
                    default -> throw new AssertionError(method.getName());
                }));
        return movement;
    }
    private static void save(LinkedHashMap<Field, Object> saved, Object actor, String name, Object value) throws Exception {
        var field = field(ClientActorBoundary.class, name); saved.put(field, field.get(actor)); field.set(actor, value);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
