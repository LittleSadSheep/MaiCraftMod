package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.actor.DefaultNativeActionPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall;

/** Shared execution with real voxel rays/receipts; the inert native boundary publishes world updates. */
public final class WaterSurfaceExecutionTest {
    public static void main(String[] ignored) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var f = new WaterLandingReplayTest.Fixture(false);
        var lower = Blocks.TALL_GRASS.defaultBlockState();
        var upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER);
        f.world.scene.blocks.put(BlockPos.ZERO,lower);
        f.world.scene.blocks.put(BlockPos.ZERO.above(),upper);
        var inventory = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.WATER),true,false,false);
        var plan = inventory.plans(f.world,BlockPos.ZERO,pos -> false).getFirst();
        check(plan.cell().equals(BlockPos.ZERO.above(2)) && plan.clicked().equals(BlockPos.ZERO.above()),
                "the executable plan uses the exposed upper plant and the actual high source cell");
        field(LandingAssistSession.class,"plan").set(f.session,plan);
        int[] uses = {0};
        NativeActionPort actions = (NativeActionPort)Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                new Class<?>[]{NativeActionPort.class},(proxy,method,args) -> switch (method.getName()) {
                    case "useItem" -> {
                        boolean pickup = f.player.getMainHandItem().is(Items.BUCKET);
                        var ray = f.world.clip(new ClipContext(f.player.getEyePosition(),
                                f.player.getEyePosition().add(f.player.getViewVector(1).scale(f.player.blockInteractionRange())),
                                ClipContext.Block.OUTLINE,pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE,f.player));
                        check(ray.getType() == HitResult.Type.BLOCK
                                        && WaterBucketFall.waterCell(f.world,ray,pickup).equals(plan.cell()),
                                "native place and pickup rays must both target the actual source, never the dry feet cell");
                        uses[0]++;
                        f.world.scene.blocks.put(plan.cell(),(pickup ? Blocks.AIR : Blocks.WATER).defaultBlockState());
                        f.player.inventory.setItem(0,new ItemStack(pickup ? Items.WATER_BUCKET : Items.BUCKET));
                        yield receipt(f,(NativeConfirmation)args[2],(Integer)args[3]);
                    }
                    case "poll" -> f.receipts.poll((LocalPlayerContext)args[0],(NativeActionReceipt)args[1]);
                    default -> throw new AssertionError("unexpected high-source native action: " + method.getName());
                });
        LocalPlayerContext context = (LocalPlayerContext)Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{LocalPlayerContext.class},(proxy,method,args) ->
                        method.getName().equals("actions") ? actions : method.invoke(f.context,args));
        f.position(3,-1,false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(context) && f.session.prepare(context),"held bucket prepares without pouring on the source platform");
        tick(f,context);
        check(uses[0] == 1 && WaterBucketFall.sourceWater(f.world.getBlockState(plan.cell()))
                        && f.world.getBlockState(BlockPos.ZERO).equals(lower)
                        && f.world.getBlockState(BlockPos.ZERO.above()).equals(upper),
                "placing the upper source does not falsely claim immediate replacement of both plant halves");

        f.position(2.5,-.03,false); f.player.wet = true; f.player.fallDistance = 0;
        check(f.player.getBoundingBox().intersects(f.world.getFluidState(plan.cell())
                        .getShape(f.world,plan.cell()).bounds().move(plan.cell())),
                "the fixture's native wet/reset state coincides with actual source-fluid body overlap");
        for (int i=0;i<15;i++) tick(f,context);
        check(Boolean.TRUE.equals(f.session.diagnostics().get("native_water_contact"))
                        && !f.session.failed() && !f.session.complete() && uses[0] == 1,
                "high-source contact outside the old two-block feet radius remains an active correct landing");

        // Native downward flow replaces the upper plant; DoublePlantBlock's real neighbor rule
        // removes its unsupported pair. Recovery owns the water source, not restoration of grass.
        var flow = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,8);
        f.world.scene.blocks.put(BlockPos.ZERO.above(),flow);
        f.world.scene.blocks.put(BlockPos.ZERO,lower.updateShape(Direction.UP,flow,null,BlockPos.ZERO,BlockPos.ZERO.above()));
        f.position(0,0,true); f.player.fallDistance = 0; f.player.setXRot(-90);
        for (int i=0;i<45 && !f.session.complete();i++) tick(f,context);
        check(f.session.complete() && !f.session.failed() && f.player.getHealth() == 20 && uses[0] == 2,
                "real source contact, supported feet and one confirmed pickup finish the high-source clutch");
        check(f.player.getMainHandItem().is(Items.WATER_BUCKET) && f.world.getBlockState(plan.cell()).isAir()
                        && f.world.getBlockState(BlockPos.ZERO).isAir()
                        && f.world.getBlockState(BlockPos.ZERO.above()).equals(flow),
                "pickup recovers its own high source and never fabricates restoration of the removed grass");
        var changes = f.session.drainChanges();
        check(changes.size() == 2 && changes.stream().allMatch(change -> change.position().equals(plan.cell()))
                        && changes.getFirst().before().isAir() && changes.getFirst().after().is(Blocks.WATER)
                        && changes.getLast().after().isAir(),
                "attributed placement and recovery record the actual source coordinate and world states");
        System.out.println("WaterSurfaceExecutionTest: passed");
    }
    private static void tick(WaterLandingReplayTest.Fixture f,LocalPlayerContext context) {
        f.time++; f.session.tick(context);
    }
    private static NativeActionReceipt receipt(WaterLandingReplayTest.Fixture f,NativeConfirmation confirmation,int timeout)
            throws Exception {
        var constructor = NativeActionReceipt.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var receipt = (NativeActionReceipt)constructor.newInstance(NativeActionReceipt.Kind.USE_ITEM,
                f.context,timeout,2,confirmation,null,null);
        field(DefaultNativeActionPort.class,"active").set(f.receipts,receipt);
        return receipt;
    }
    private static Field field(Class<?> type,String name) throws Exception {
        var value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
