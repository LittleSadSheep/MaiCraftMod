package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.DefaultNativeActionPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;

/**
 * 把补料接入落地会话，检查站在出发处不会误判落地、落到别处仍等界面收尾，以及停止待确认水桶操作时先松开使用动作。
 */
public final class LandingSupplyCleanupTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        groundedPreparationKeepsSupplying(); carriedHayKeepsTryingWater(); offTargetWaterRetainsSupply(); pendingBucketUsesPhysicalRelease();
        System.out.println("LandingSupplyCleanupTest: passed");
    }

    private static void groundedPreparationKeepsSupplying() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(12,0,true); f.player.inventory.setItem(0,ItemStack.EMPTY);
        var transaction = new Supply();
        var supply = new LandingMaterialSupply(List.of(ResourceLocation.parse("minecraft:water_bucket")),
                (player,request) -> transaction);
        var session = LandingAssistSession.automatic(List.of(f.session.plan()),false);
        field(LandingAssistSession.class,"materialSupply").set(session,supply);
        for (int tick=0;tick<15;tick++) { f.time++; session.tick(f.context); }
        check(transaction.ticks == 15 && transaction.finishes == 0 && !session.failed() && !session.complete(),
                "standing on the departure platform while acquiring protection is not an off-target touchdown");
    }

    private static void offTargetWaterRetainsSupply() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(12, -1, false); f.player.inventory.setItem(0, ItemStack.EMPTY);
        var menu = (InventoryMenu) f.memory.allocateInstance(InventoryMenu.class);
        field(AbstractContainerMenu.class,"carried").set(menu,ItemStack.EMPTY);
        field(LocalPlayer.class,"inventoryMenu").set(f.player,menu); f.player.containerMenu = menu;
        var transaction = new Supply();
        var supply = new LandingMaterialSupply(List.of(ResourceLocation.parse("minecraft:water_bucket")),
                (player,request) -> transaction);
        supply.tick(f.context,50);
        field(LandingAssistSession.class,"materialSupply").set(f.session,supply);
        f.player.wet = true; f.player.fallDistance = 0;
        f.position(0,0,false);
        field(LocalPlayer.class,"position").set(f.player,new Vec3(5.5,0,.5));
        for (int tick=0;tick<15;tick++) f.tick();
        check(transaction.finishes > 0 && transaction.ticks == 1
                        && supply.cleanupPending() && !f.session.complete(),
                "an off-target water landing stops acquisition and retains its pending cleanup owner");
        transaction.closed = true;
        for (int tick=0;tick<15;tick++) f.tick();
        check(!supply.cleanupPending() && f.session.complete() && f.session.failed(),
                "unverified landing finishes only after AE cleanup settles");
    }

    private static void carriedHayKeepsTryingWater() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(12,0,true); f.player.inventory.setItem(0,new ItemStack(net.minecraft.world.item.Items.HAY_BLOCK));
        var water = f.session.plan();
        var hay = new LandingAssistPlan(LandingAssistPlan.Kind.HAY,water.feet(),water.cell(),water.clicked(),water.face(),false);
        var session = LandingAssistSession.automatic(List.of(hay,water),false);
        var transaction = new Supply();
        var supply = new LandingMaterialSupply(List.of(ResourceLocation.parse("minecraft:hay_block"),
                ResourceLocation.parse("minecraft:water_bucket")),(player,request) -> transaction);
        field(LandingAssistSession.class,"materialSupply").set(session,supply);
        f.time++; session.tick(f.context);
        check(transaction.ticks == 1 && transaction.finishes == 0 && !session.failed()
                        && session.plan().kind() == LandingAssistPlan.Kind.WATER,
                "the shared landing session keeps carried hay as fallback while acquiring damage-free water");
    }

    private static void pendingBucketUsesPhysicalRelease() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var instance = field(net.minecraft.client.Minecraft.class,"instance"); Object previous = instance.get(null);
        field(net.minecraft.client.Minecraft.class,"gameThread").set(f.minecraft,Thread.currentThread());
        instance.set(null,f.minecraft);
        try {
        f.position(2,-1,false); f.player.setXRot(90);
        f.blockEvidence = false; f.inventoryEvidence = false;
        check(f.session.prepareAlreadyHeld(f.context),"held bucket prepares the real pending-use scenario");
        f.tick();
        check(f.uses == 1,"one native bucket use has been submitted");
        boolean[] mutation = {false}, released = {false}; int[] releases = {0};
        NativeActionPort actions = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                new Class<?>[]{NativeActionPort.class},(proxy,method,args) -> switch (method.getName()) {
                    case "poll" -> f.receipts.poll((LocalPlayerContext)args[0],(NativeActionReceipt)args[1]);
                    case "retireOneShotForTaskBoundary" -> f.receipts.retireOneShotForTaskBoundary(
                            (LocalPlayerContext)args[0],(NativeActionReceipt)args[1],(String)args[2]);
                    case "releaseUsingItem" -> {
                        check(((NativeActionReceipt)args[1]).kind() == NativeActionReceipt.Kind.USE_ITEM,
                                "physical release belongs to the pending native item use");
                        releases[0]++;
                        var constructor = NativeActionReceipt.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
                        NativeConfirmation confirmation = c -> released[0]
                                ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
                        var receipt = (NativeActionReceipt)constructor.newInstance(NativeActionReceipt.Kind.RELEASE_ITEM,
                                args[0],10,2,confirmation,null,null);
                        field(DefaultNativeActionPort.class,"active").set(f.receipts,receipt);
                        yield receipt;
                    }
                    default -> throw new AssertionError("unexpected cancellation mutation: " + method.getName());
                });
        LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class},(proxy,method,args) -> switch (method.getName()) {
                    case "actions" -> actions;
                    case "mutationAvailable" -> mutation[0];
                    default -> method.invoke(f.context,args);
                });
        f.session.stop(context,"test cancellation while bucket receipt is pending");
        check(f.session.cleanupPending() && releases[0] == 0,
                "an occupied native mutation slot defers physical release without dropping ownership");
        mutation[0] = true; f.time++; f.session.tick(context);
        check(releases[0] == 1 && f.session.cleanupPending(),
                "USE_ITEM gets its dedicated release and a pending release is retained");
        released[0] = true;
        for (int tick=0;tick<3;tick++) { f.time++; f.session.tick(context); }
        check(releases[0] == 1 && !f.session.cleanupPending() && f.session.failed()
                        && f.session.drainChanges().isEmpty()
                        && Boolean.FALSE.equals(f.session.diagnostics().get("confirmed_own_placement")),
                "release confirmation is not falsely recorded as a successful placement or repeated use");
        } finally { instance.set(null,previous); }
    }

    private static final class Supply implements Ae2ResourceSupply.Session {
        int ticks,finishes; boolean closed;
        public Optional<Ae2ResourceSupply.Outcome> tick(LocalPlayerContext context) { ticks++; return Optional.empty(); }
        public Optional<Ae2ResourceSupply.Outcome> outcome() {
            return closed ? Optional.of(new Ae2ResourceSupply.Outcome(Ae2ResourceSupply.Status.CANCELLED,
                    "native_close_confirmed","",List.of(),Ae2ResourceSupply.Operation.SUPPLY,
                    false,0,0,false,false,"wireless",List.of())) : Optional.empty();
        }
        public Optional<Ae2ResourceSupply.Outcome> finishInPlace(LocalPlayerContext context,String reason) {
            finishes++; return outcome();
        }
        public String phase() { return "pending_native_close"; }
        public boolean livenessActive() { return !closed; }
        public void pause(LocalPlayerContext context) { }
        public Ae2ResourceSupply.Outcome cancel(LocalPlayerContext context,String reason) {
            throw new AssertionError("must await the real asynchronous cleanup contract");
        }
    }
    private static Field field(Class<?> type,String name) throws Exception {
        for (Class<?> owner=type;owner!=null;owner=owner.getSuperclass()) {
            try { var value=owner.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
