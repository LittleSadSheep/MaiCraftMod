package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.Settings;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan;

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
        var ground = new Pool(landing,air,stone);
        var inventory = new LandingAssistPlan.InventorySnapshot(java.util.Set.of(LandingAssistPlan.Kind.WATER),true,false,false);
        check(!inventory.plans(ground,landing,pos -> policy.protects(pos.getX(),pos.getY(),pos.getZ())).isEmpty(),
                "a protected support was mistaken for the water mutation cell");
        check(inventory.plans(ground,landing,landing::equals).isEmpty(), "protected water cell must fail before falling");
        check(!WaterBucketFall.replaceableByWater(stone), "clutch may not replace a building block");
        check(WaterBucketFall.replaceableByWater(Blocks.SHORT_GRASS.defaultBlockState()),
                "native fluid replacement admits ordinary grass during self-rescue");
        var stair = Blocks.STONE_STAIRS.defaultBlockState();
        var stairWorld = new Pool(landing,air,stair);
        check(inventory.plans(stairWorld,landing,pos -> false).getFirst().cell().equals(landing.below()),
                "waterlogged support is the actual water cell, never the air above");
        var groundHit = new BlockHitResult(Vec3.atBottomCenterOf(landing), Direction.UP, landing.below(), false);
        check(WaterBucketFall.waterCell(ground,groundHit,false).equals(landing), "filled bucket actual water cell");
        check(WaterBucketFall.waterCell(stairWorld,groundHit,false).equals(landing.below()), "native waterlogging target");
        var pool = new Pool(landing);
        Vec3 eye = Vec3.atCenterOf(landing.above(2)), end = eye.add(0, -4, 0);
        var solid = pool.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        var source = pool.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, CollisionContext.empty()));
        check(solid.getType() == HitResult.Type.MISS && source.getType() == HitResult.Type.BLOCK,
                "empty bucket must trace source fluids even with no solid floor in reach");
        check(WaterBucketFall.waterCell(pool,source,true).equals(landing),
                "pickup confirmation must observe the source water, not its distant support");
        var water = Blocks.WATER.defaultBlockState();
        check(!WaterBucketFall.canRecover(water, false, false), "never remove a pre-existing pool");
        check(!WaterBucketFall.canRecover(water, true, true), "explicit protection also guards recovery");
        check(WaterBucketFall.canRecover(water, true, false), "confirmed own clutch water is recoverable");
        singleStrategyBoundary();
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

    private static void singleStrategyBoundary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("common/src/main/java/baritone"))) root = root.getParent();
        check(root != null, "run from the repository");
        String base = "common/src/main/java/";
        String fall = Files.readString(root.resolve(base + "baritone/pathing/movement/movements/MovementFall.java"));
        String cost = Files.readString(root.resolve(base + "baritone/pathing/movement/movements/MovementDescend.java"));
        String bridge = Files.readString(root.resolve(base + "org/maiwithu/maicraft/core/pathing/baritone/EmbeddedBaritoneActionBridge.java"));
        check(fall.contains("LandingAssistSession") && cost.contains("landingPlans(")
                        && !fall.contains("willPlaceBucket") && !fall.contains("waterPlacement")
                        && !cost.contains("hasWaterBucket") && !bridge.contains("startWaterUse")
                        && !bridge.contains("submitItemUse"),
                "water must use the same guarded plan/session, never a second legacy admission or receipt path");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private record Pool(BlockPos source, BlockState sourceState, BlockState support) implements BlockGetter {
        private Pool(BlockPos source) { this(source,Blocks.WATER.defaultBlockState(),Blocks.AIR.defaultBlockState()); }
        public BlockState getBlockState(BlockPos pos) { return pos.equals(source) ? sourceState : pos.equals(source.below()) ? support : Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
