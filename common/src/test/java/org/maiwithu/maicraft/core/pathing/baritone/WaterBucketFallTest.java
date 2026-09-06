package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.Settings;
import baritone.pathing.movement.movements.MovementFall;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import sun.misc.Unsafe;

/** Production permission wiring, native bucket geometry and confirmed clutch ownership. */
public final class WaterBucketFallTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        permissions();
        BlockPos landing = new BlockPos(-81, 103, -4);
        var air = Blocks.AIR.defaultBlockState();
        var stone = Blocks.STONE.defaultBlockState();
        var policy = EmbeddedBaritonePolicy.capture(GoalCompiler.standOn(landing).sacred(),
                LongSets.singleton(landing.below().asLong()), LongSets.emptySet());
        check(WaterBucketFall.canPlace(air, stone,
                policy.protects(landing.getX(), landing.getY(), landing.getZ())),
                "a protected support was mistaken for the water mutation cell");
        check(!WaterBucketFall.canPlace(air, stone, true), "protected water cell must fail before falling");
        check(!WaterBucketFall.canPlace(stone, stone, false), "clutch may not replace a building block");
        check(!WaterBucketFall.canPlace(Blocks.SHORT_GRASS.defaultBlockState(), stone, false),
                "water-only must not destroy the occupied landing cell");
        var stair = Blocks.STONE_STAIRS.defaultBlockState();
        check(!WaterBucketFall.canPlace(air, stair, false), "waterlogging support cannot cushion the air cell above");
        var groundHit = new BlockHitResult(Vec3.atBottomCenterOf(landing), Direction.UP, landing.below(), false);
        check(WaterBucketFall.waterCell(groundHit, stone, false).equals(landing), "filled bucket actual water cell");
        check(WaterBucketFall.waterCell(groundHit, stair, false).equals(landing.below()), "native waterlogging target");
        var pool = new Pool(landing);
        Vec3 eye = Vec3.atCenterOf(landing.above(2)), end = eye.add(0, -4, 0);
        var solid = pool.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        var source = pool.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, CollisionContext.empty()));
        check(solid.getType() == HitResult.Type.MISS && source.getType() == HitResult.Type.BLOCK,
                "empty bucket must trace source fluids even with no solid floor in reach");
        check(WaterBucketFall.waterCell(source, pool.getBlockState(source.getBlockPos()), true).equals(landing),
                "pickup confirmation must observe the source water, not its distant support");
        var water = Blocks.WATER.defaultBlockState();
        check(!WaterBucketFall.canRecover(water, false, false), "never remove a pre-existing pool");
        check(!WaterBucketFall.canRecover(water, true, true), "explicit protection also guards recovery");
        check(WaterBucketFall.canRecover(water, true, false), "confirmed own clutch water is recoverable");
        ownership();
        System.out.println("WaterBucketFallTest: passed");
    }

    private static void permissions() throws Exception {
        var constructor = Settings.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var settings = constructor.newInstance();
        for (var permit : TerrainPermit.values()) {
            EmbeddedBaritoneRuntime.configureTerrain(settings, permit);
            check(settings.allowBreak.value == permit.mayAlter() && settings.allowPlace.value == permit.mayAlter()
                            && settings.allowParkourPlace.value == permit.mayAlter() && settings.allowDownward.value == permit.mayAlter(),
                    "water-only widened ordinary terrain mutation");
            check(settings.allowWaterBucketFall.value == permit.mayUseWaterBucket(), "bucket switch lost independent permission");
        }
        check(PlayerNav.ContextProvider.WATER_ONLY.permit() == TerrainPermit.WATER_ONLY, "public navigation provider");
    }

    private static void ownership() throws Exception {
        var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        var memory = (Unsafe) memoryField.get(null);
        MovementFall fall = (MovementFall) memory.allocateInstance(MovementFall.class);
        check(!fall.hasPlacedWater(), "an existing pool has no owned placement receipt");
        var ctor = NativeActionReceipt.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        var context = proxy(LocalPlayerContext.class, (p, m, a) -> 1L);
        var finish = NativeActionReceipt.class.getDeclaredMethod("finish", NativeActionReceipt.Status.class, String.class);
        finish.setAccessible(true);
        for (var status : new NativeActionReceipt.Status[]{NativeActionReceipt.Status.UNCERTAIN, NativeActionReceipt.Status.CONFIRMED_APPLIED}) {
            var receipt = (NativeActionReceipt) ctor.newInstance(NativeActionReceipt.Kind.USE_ITEM, context, 20, 2, NativeConfirmation.pending(), null, null);
            fall.waterPlacementSubmitted(receipt);
            check(!fall.hasPlacedWater(), "an unconfirmed submission is not ownership evidence");
            finish.invoke(receipt, status, "test native outcome");
            check(fall.hasPlacedWater() == (status == NativeActionReceipt.Status.CONFIRMED_APPLIED), "only confirmed placement owns recovery");
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private record Pool(BlockPos source) implements BlockGetter {
        public BlockState getBlockState(BlockPos pos) { return pos.equals(source) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
