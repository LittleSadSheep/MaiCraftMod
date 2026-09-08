package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;

/** Actual emergency selection and native voxel-ray submission for the reported high-speed fall. */
public final class EmergencyWaterSelectionTest {
    private static final double LIVE_DROP = 39.4838661356948;
    private static final double LIVE_SPEED = 2.603278959908224;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var memory = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var client = (net.minecraft.client.Minecraft) memory.allocateInstance(net.minecraft.client.Minecraft.class);
        field(net.minecraft.client.Minecraft.class, "gameThread").set(client, Thread.currentThread());
        var instance = field(net.minecraft.client.Minecraft.class, "instance"); Object previous = instance.get(null);
        instance.set(null, client);
        try { reportedFall(); surfaces(); earlyProbe(); invalidNativeRay(); }
        finally { instance.set(null, previous); }
        System.out.println("EmergencyWaterSelectionTest: passed");
    }

    private static void reportedFall() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(LIVE_DROP, -LIVE_SPEED, false);
        check(!new WaterLandingWindow(.08, 4.5, 1.62, LIVE_SPEED).permits(LIVE_DROP),
                "the reported fall has no guarantee for every possible departure phase");
        var session = EmergencyLanding.find(f.context);
        assertWater(session, "an already-airborne opportunity must retain its carried water bucket");
        double height = LIVE_DROP, velocity = -LIVE_SPEED;
        int ticks = 0;
        while (height > 0 && f.uses == 0) {
            f.position(height, velocity, false);
            f.time++; EmergencyLanding.tick(f.context, session);
            check(!session.failed(), "dry-fall guarantee cannot prematurely fail an actual bucket opportunity");
            height += velocity; velocity = (velocity - .08) * (double) .98F;
            ticks++;
        }
        check(ticks > 1 && f.uses == 1 && f.world.water && !f.player.onGround(),
                "the real native bucket ray submits once before the reported fall reaches its floor");
        check(Boolean.TRUE.equals(session.diagnostics().get("submitted")),
                "a retained candidate is accompanied by an actual native action submission");
        f.position(0, 0, true); f.player.wet = true; f.player.fallDistance = 0;
        for (int i = 0; i < 60 && !session.complete(); i++) {
            f.time++; EmergencyLanding.tick(f.context, session);
        }
        check(session.complete() && !session.failed() && f.uses == 2 && !f.world.water,
                "source receipt, native water contact and attributable recovery finish the replay");
    }

    private static void surfaces() throws Exception {
        for (int surface = 0; surface < 3; surface++) {
            var f = new WaterLandingReplayTest.Fixture(false);
            BlockPos source;
            if (surface == 0) {
                f.world.scene.blocks.put(BlockPos.ZERO.below(), Blocks.STONE_SLAB.defaultBlockState());
                source = BlockPos.ZERO.below();
            } else {
                BlockState grass = (surface == 1 ? Blocks.SHORT_GRASS : Blocks.TALL_GRASS).defaultBlockState();
                f.world.scene.blocks.put(BlockPos.ZERO, grass);
                if (surface == 2) f.world.scene.blocks.put(BlockPos.ZERO.above(),
                        grass.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
                source = surface == 1 ? BlockPos.ZERO : BlockPos.ZERO.above(2);
            }
            f.position(LIVE_DROP, -LIVE_SPEED, false);
            var session = EmergencyLanding.find(f.context);
            assertWater(session, "valid bottom-slab and grass geometry cannot lose water to a speed guarantee");
            check(session.plan().cell().equals(source), "the emergency plan retains its actual native source cell");
        }
        var top = new WaterLandingReplayTest.Fixture(false);
        top.world.scene.blocks.put(BlockPos.ZERO.below(), Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        top.position(LIVE_DROP, -LIVE_SPEED, false);
        var alternative = EmergencyLanding.find(top.context);
        check(alternative == null || !((List<?>) alternative.diagnostics().get("candidate_strategies")).contains("WATER"),
                "water below a top slab's impact floor is still rejected by real contact geometry");
    }

    private static void earlyProbe() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.player.inventory.setItem(0, ItemStack.EMPTY);
        f.position(96, -.08, false);
        check(EmergencyLanding.triggered(f.player), "the first descending tick above 40 blocks already predicts injury");
        var session = EmergencyLanding.find(f.context);
        assertWater(session, "missing carried items may begin conditional supply before the final 40 blocks");
        var network = new PendingSupply(); int[] begins = {0};
        var water = ResourceLocation.parse("minecraft:water_bucket");
        var supply = new LandingMaterialSupply(List.of(water), (player, request) -> {
            begins[0]++;
            check(player == f.player && request.acceptedItemIds().contains(water)
                            && !request.allowCrafting() && request.totalCount() == 1,
                    "the first reflex requests one suitable stocked aid, including water, without crafting");
            return network;
        });
        field(LandingAssistSession.class, "materialSupply").set(session, supply);
        f.time++; session.tick(f.context);
        check(begins[0] == 1 && network.ticks == 1 && f.player.getY() == 96
                        && supply.result().state() == LandingMaterialSupply.State.ACQUIRING,
                "the first dangerous descending tick already begins and ticks AE access above 40 blocks");
        check(f.uses == 0 && f.player.getInventory().isEmpty(),
                "starting native supply cannot claim a material was acquired or placed");

        var tracked = (TrackedLevel) f.memory.allocateInstance(TrackedLevel.class);
        tracked.scene = f.world.scene; tracked.dimension = f.world.dimension;
        field(net.minecraft.world.entity.Entity.class, "level").set(f.player, tracked);
        var context = (org.maiwithu.maicraft.client.actor.LocalPlayerContext) java.lang.reflect.Proxy.newProxyInstance(
                org.maiwithu.maicraft.client.actor.LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{org.maiwithu.maicraft.client.actor.LocalPlayerContext.class},
                (proxy, method, arguments) -> method.getName().equals("level") ? tracked : method.invoke(f.context, arguments));
        f.position(2_000_000, -.08, false);
        assertWater(EmergencyLanding.find(context), "world-exterior altitude does not hide loaded ground");
        check(tracked.groundProbes == 5 && tracked.longestRay <= 384 && tracked.highestStart == 320,
                "probing skips millions of world-exterior air cells and stays within build height");
        tracked.unloaded = true; tracked.clips = 0;
        check(EmergencyLanding.find(context) == null && tracked.clips == 0,
                "an unloaded column is never probed as if its support were known");
    }

    private static void invalidNativeRay() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(96, -LIVE_SPEED, false);
        f.world.nativeHit = new BlockHitResult(new Vec3(5_000_000.5, 0, .5), Direction.UP,
                new BlockPos(5_000_000, -1, 0), false);
        check(EmergencyLanding.find(f.context) == null,
                "a Sable plot-storage ray cannot become a world-space landing when probe range expands");
        f.world.nativeHit = new BlockHitResult(new Vec3(.5, -1000, .5), Direction.UP,
                new BlockPos(0, -1001, 0), false);
        check(EmergencyLanding.find(f.context) == null, "a returned hit outside the effective ray span is rejected");
    }

    private static void assertWater(LandingAssistSession session, String message) {
        check(session != null && session.plan().kind() == LandingAssistPlan.Kind.WATER
                && ((List<?>) session.diagnostics().get("candidate_strategies")).contains("WATER"), message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static final class PendingSupply implements Ae2ResourceSupply.Session {
        int ticks;
        public Optional<Ae2ResourceSupply.Outcome> tick(LocalPlayerContext context) { ticks++; return Optional.empty(); }
        public Optional<Ae2ResourceSupply.Outcome> outcome() { return Optional.empty(); }
        public String phase() { return "native_access_pending"; }
        public boolean livenessActive() { return true; }
        public void pause(LocalPlayerContext context) { throw new AssertionError("early acquisition must not pause"); }
        public Ae2ResourceSupply.Outcome cancel(LocalPlayerContext context, String reason) {
            throw new AssertionError("early acquisition must not cancel");
        }
        public Optional<Ae2ResourceSupply.Outcome> finishInPlace(LocalPlayerContext context, String reason) {
            throw new AssertionError("96-block first-descent acquisition has not reached its action deadline");
        }
    }
    private static final class TrackedLevel extends ClientLevel {
        WaterLandingReplayTest.Scene scene;
        net.minecraft.world.level.dimension.DimensionType dimension;
        int clips, groundProbes; double longestRay, highestStart; boolean unloaded;
        private TrackedLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        public boolean isLoaded(BlockPos pos) { return !unloaded; }
        public net.minecraft.world.level.dimension.DimensionType dimensionType() { return dimension; }
        public BlockHitResult clip(ClipContext context) {
            if (context.getTo().y == getMinBuildHeight()) groundProbes++;
            clips++; longestRay = Math.max(longestRay, context.getFrom().distanceTo(context.getTo()));
            highestStart = Math.max(highestStart, context.getFrom().y);
            return scene.clip(context);
        }
        public BlockState getBlockState(BlockPos pos) { return scene.getBlockState(pos); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
