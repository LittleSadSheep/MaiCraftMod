package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** Execute actual BucketItem.use; the fixture supplies only the native world's storage and sound boundary. */
public final class NativeBucketLandingTest {
    public static void main(String[] ignored) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var flower : List.of(Blocks.POPPY,Blocks.DANDELION,Blocks.BLUE_ORCHID,Blocks.SHORT_GRASS))
            landing(flower.defaultBlockState(),false);
        landing(Blocks.AIR.defaultBlockState(),true);
        landing(Blocks.POPPY.defaultBlockState(),false,true);
        System.out.println("NativeBucketLandingTest: passed");
    }

    private static void landing(BlockState flower, boolean slabSide) throws Exception {
        landing(flower,slabSide,false);
    }
    private static void landing(BlockState flower, boolean slabSide, boolean plantTopHit) throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        field(Level.class,"isClientSide").setBoolean(f.world,true);
        f.world.scene.blocks.put(BlockPos.ZERO,flower);
        if (slabSide) f.world.scene.blocks.put(BlockPos.ZERO.below(),Blocks.STONE_SLAB.defaultBlockState());
        var inventory = LandingAssistPlan.InventorySnapshot.capture(f.player,
                org.maiwithu.maicraft.core.pathing.moves.TerrainPermit.LANDING_ONLY,false);
        var plan = inventory.plans(f.world,BlockPos.ZERO,pos -> false).getFirst();
        var source = slabSide ? BlockPos.ZERO.below() : plantTopHit ? BlockPos.ZERO.above() : BlockPos.ZERO;
        check(plan.cell().equals(slabSide ? BlockPos.ZERO.below() : BlockPos.ZERO),"initial source must be inside " + flower + "; actual plan=" + plan);
        var session = LandingAssistSession.automatic(List.of(plan),true);
        int[] uses = {0};
        var actions = (NativeActionPort)Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                new Class<?>[]{NativeActionPort.class},(proxy,method,args) -> switch (method.getName()) {
                    case "useItem" -> {
                        var hand = (InteractionHand)args[1];
                        ItemStack held = f.player.getItemInHand(hand);
                        // Waterlogging publishes its block change on the server side only.
                        field(Level.class,"isClientSide").setBoolean(f.world,!slabSide || held.is(Items.BUCKET));
                        var result = held.getItem().use(f.world,f.player,hand);
                        field(Level.class,"isClientSide").setBoolean(f.world,true);
                        check(result.getResult().consumesAction(),"the actual vanilla bucket use succeeds");
                        f.player.inventory.setItem(f.player.inventory.selected,result.getObject()); uses[0]++;
                        yield receipt(f,(NativeConfirmation)args[2],(Integer)args[3]);
                    }
                    case "poll" -> f.receipts.poll((LocalPlayerContext)args[0],(NativeActionReceipt)args[1]);
                    default -> throw new AssertionError(method.getName());
                });
        var context = (LocalPlayerContext)Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class},(proxy,method,args) ->
                        method.getName().equals("actions") ? actions : method.invoke(f.context,args));
        f.position(2,-.4,false);
        if (slabSide) {
            f.world.scene.blocks.put(BlockPos.ZERO.below().west(),Blocks.AIR.defaultBlockState());
            field(net.minecraft.world.entity.Entity.class,"position").set(f.player,new Vec3(-.4,-.25,.5));
            look(f,new Vec3(0,-.75,.5));
            var hit = f.world.clip(new ClipContext(f.player.getEyePosition(),f.player.getEyePosition()
                    .add(f.player.getViewVector(1).scale(4.5)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,f.player));
            check(hit.getDirection() == Direction.WEST && hit.getBlockPos().equals(source),"fixture actually hits the slab side");
            session.tick(context);
        } else if (plantTopHit) {
            look(f,f.world.getBlockState(BlockPos.ZERO).getShape(f.world,BlockPos.ZERO).bounds().getCenter());
            session.tick(context);
            check(session.plan().cell().equals(source),"an actual plant-top ray rebinds its legal water cell before submission");
        } else { look(f,plan.aimPoint()); EmergencyLanding.tick(context,session); }
        check(uses[0] == 1 && f.player.getMainHandItem().is(Items.BUCKET)
                        && LandingAssistPlan.existingSafe(LandingAssistPlan.Kind.WATER,f.world.getBlockState(source)),
                "native water submission: uses=" + uses[0] + "; state=" + f.world.getBlockState(source) + "; " + session.diagnostics());
        f.position(slabSide ? -.5 : 0,0,true); f.player.wet = true; f.player.fallDistance = 0;
        check(f.player.getBoundingBox().intersects(f.world.getFluidState(source).getShape(f.world,source).bounds().move(source)),
                "simulated wet/reset feedback has actual source-water body contact");
        for (int tick=0;tick<60 && !session.complete();tick++) { f.time++; EmergencyLanding.tick(context,session); }
        check(session.complete() && !session.failed() && uses[0] == 2 && f.player.getMainHandItem().is(Items.WATER_BUCKET),
                "the native source is picked up after contact and supported landing");
        check(f.world.getBlockState(source).equals(slabSide ? Blocks.STONE_SLAB.defaultBlockState() : Blocks.AIR.defaultBlockState()),
                "pickup restores the dry slab or clears only the owned replacement water");
        var changes = session.drainChanges();
        check(changes.size() == 2 && changes.getFirst().before().equals(slabSide ? Blocks.STONE_SLAB.defaultBlockState()
                        : plantTopHit ? Blocks.AIR.defaultBlockState() : flower),
                "the real pre-placement flower or slab is retained in the effect record");
    }

    private static void look(WaterLandingReplayTest.Fixture f, Vec3 point) {
        var delta = point.subtract(f.player.getEyePosition());
        f.player.setYRot((float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
        f.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
    }
    private static NativeActionReceipt receipt(WaterLandingReplayTest.Fixture f, NativeConfirmation confirmation,int timeout) throws Exception {
        var method = WaterLandingReplayTest.Fixture.class.getDeclaredMethod("receipt",NativeActionReceipt.Kind.class,NativeConfirmation.class,int.class);
        method.setAccessible(true); return (NativeActionReceipt)method.invoke(f,NativeActionReceipt.Kind.USE_ITEM,confirmation,timeout);
    }
    private static Field field(Class<?> type,String name) throws Exception {
        for (var owner=type;owner!=null;owner=owner.getSuperclass()) {
            try { var value=owner.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
