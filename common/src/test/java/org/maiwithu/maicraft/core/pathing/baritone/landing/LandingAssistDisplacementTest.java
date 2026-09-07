package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.core.task.chain.MLGChain;

/** Real landing session and native receipt polling after flow/knockback misses the planned cell. */
public final class LandingAssistDisplacementTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (String support : new String[]{"ground", "water", "climbable"}) safeElsewhere(support);
        pendingReceiptRetainsOwner();
        airborneAndInterruptedDwell();
        System.out.println("LandingAssistDisplacementTest: passed");
    }

    private static void safeElsewhere(String support) throws Exception {
        var f = placed();
        LocalPlayer player = f.player;
        if (support.equals("climbable")) player = (Climber) f.memory.allocateInstance(Climber.class);
        position(player, true, support);
        LocalPlayerContext context = withPlayer(f, player);
        var reflex = new MLGChain(); field(MLGChain.class, "session").set(reflex, f.session);
        check(reflex.canRun(player), "an active displaced fall still owns its reflex before settlement");
        for (int tick = 0; tick < 9; tick++) { f.time++; f.session.tick(context); }
        check(!f.session.complete(), "one safe sample cannot terminate the displaced fall");
        f.time++; f.session.tick(context);
        check(f.session.complete() && f.session.failed() && !reflex.canRun(player),
                support + " away from the planned cell must terminate with failure and yield reflex ownership");
        check(f.uses == 1 && f.world.water && !Boolean.TRUE.equals(f.session.diagnostics().get("removed_own_aid")),
                "off-target settlement must retain the confirmed aid without a recovery mutation");
    }

    private static void pendingReceiptRetainsOwner() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.inventoryEvidence = false;
        f.position(2, -1, false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(f.context), "held landing item is ready"); f.tick();
        NativeActionReceipt pending = (NativeActionReceipt) field(LandingAssistSession.class, "receipt").get(f.session);
        position(f.player, true, "ground");
        for (int tick = 0; tick < 10; tick++) f.tick();
        check(!f.session.complete() && !pending.terminal()
                        && field(LandingAssistSession.class, "receipt").get(f.session) == pending,
                "even a full displaced dwell must not discard an unresolved placement receipt");
        for (int tick = 0; tick < 20 && !f.session.complete(); tick++) f.tick();
        check(pending.terminal() && f.session.complete() && f.session.failed() && f.world.water && f.uses == 1,
                "after bounded receipt failure, stable off-target support must finish without recovering unowned water");
    }

    private static void airborneAndInterruptedDwell() throws Exception {
        var f = placed();
        position(f.player, false, "ground");
        for (int tick = 0; tick < 30; tick++) f.tick();
        check(!f.session.complete(), "an airborne body far from the selected aid must retain its fall owner");
        position(f.player, true, "ground");
        for (int tick = 0; tick < 9; tick++) f.tick();
        position(f.player, false, "ground"); f.tick();
        position(f.player, true, "ground");
        for (int tick = 0; tick < 9; tick++) f.tick();
        check(!f.session.complete(), "losing support must restart the displaced settlement dwell");
        f.tick(); check(f.session.complete() && f.session.failed() && f.uses == 1,
                "renewed sustained support finishes failure without another placement or pickup");
    }

    private static WaterLandingReplayTest.Fixture placed() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(2, -1, false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(f.context), "held landing item is ready");
        f.tick(); f.tick(); f.tick();
        check(f.uses == 1 && Boolean.TRUE.equals(f.session.diagnostics().get("confirmed_own_placement")),
                "fixture must obtain real native receipt confirmation before displacement");
        return f;
    }

    private static void position(LocalPlayer player, boolean settled, String support) throws Exception {
        field(LocalPlayer.class, "position").set(player, new Vec3(4.5, settled ? 0 : 4, .5));
        field(LocalPlayer.class, "blockPosition").set(player, new BlockPos(4, settled ? 0 : 4, 0));
        field(LocalPlayer.class, "deltaMovement").set(player, new Vec3(0, settled ? 0 : -0.8, 0));
        field(LocalPlayer.class, "onGround").setBoolean(player, settled && support.equals("ground"));
        player.fallDistance = settled ? 0 : 4;
        if (player instanceof WaterLandingReplayTest.TestPlayer ordinary) ordinary.wet = settled && support.equals("water");
    }

    private static LocalPlayerContext withPlayer(WaterLandingReplayTest.Fixture f, LocalPlayer player) {
        return (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> method.getName().equals("player")
                        ? player : method.invoke(f.context, args));
    }

    private static final class Climber extends LocalPlayer {
        private Climber() { super(null, null, null, null, null, false, false); }
        public float getHealth() { return 20; }
        public float getAbsorptionAmount() { return 0; }
        public boolean isInWater() { return false; }
        public boolean onClimbable() { return true; }
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
