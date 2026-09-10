package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;

/**
 * 检查寻路能否先选“待准备辅助物”的落差，以及没有辅助物时怎样拒绝受伤落地；不会把计划能补料当作背包已经有料。
 */
public final class AutomaticFallAdmissionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var f = new WaterLandingReplayTest.Fixture(false);
        field(net.minecraft.client.Minecraft.class, "gameThread").set(f.minecraft, Thread.currentThread());
        field(net.minecraft.client.Minecraft.class, "gameDirectory").set(f.minecraft, new java.io.File("automatic-fall-settings-fixture"));
        var instance = field(net.minecraft.client.Minecraft.class, "instance"); Object previous = instance.get(null);
        instance.set(null, f.minecraft);
        try { admission(f); } finally { instance.set(null, previous); }
        System.out.println("AutomaticFallAdmissionTest: passed");
    }

    private static void admission(WaterLandingReplayTest.Fixture f) throws Exception {
        var blocks = (BlocksView) f.memory.allocateInstance(BlocksView.class);
        blocks.world = f.world;
        field(BlockStateInterface.class, "access").set(blocks, f.world);
        var context = (CalculationContext) f.memory.allocateInstance(CalculationContext.class);
        field(CalculationContext.class, "bsi").set(context, blocks);
        field(CalculationContext.class, "world").set(context, f.world);
        field(CalculationContext.class, "precomputedData").set(context, new PrecomputedData());
        field(CalculationContext.class, "fallOrigin").set(context, new BlockPos(0, 6, 0));
        field(CalculationContext.class, "fallOriginY").set(context, 6.0);
        field(CalculationContext.class, "fallDamageBudget").set(context,
                new FallDamageBudget(20, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false));
        field(CalculationContext.class, "waterLandingWindow").set(context, new WaterLandingWindow(0.08, 4.5, 1.62, 0));
        field(CalculationContext.class, "maicraftPolicy").set(context, EmbeddedBaritonePolicy.capture(null, null, null));
        context.minFallHeight = 3;
        inventory(context, Set.of());

        var result = new MutableMoveResult();
        check(!fall(context, 6, result) && result.cost == ActionCosts.COST_INF,
                "nonfatal damage cannot silently admit an unprotected drop after automatic supply is unavailable");
        field(CalculationContext.class, "automaticLandingSupply").setBoolean(context, true);
        check(fall(context, 6, result) && result.y == 0 && result.cost < ActionCosts.COST_INF,
                "empty inventory may plan a geometrically valid fall conditional on preparing protection");
        var potential = context.landingPlans(new BlockPos(1, 0, 0), 24);
        check(potential.stream().anyMatch(plan -> plan.kind() == LandingAssistPlan.Kind.WATER),
                "24-block water protection remains feasible without an LLM inventory preparation step");
        check(context.landingInventory.available().isEmpty(), "conditional supplies never become observed inventory");

        inventory(context, Set.of(LandingAssistPlan.Kind.SLIME));
        check(context.landingPlans(new BlockPos(1, 0, 0), 6).getFirst().kind() == LandingAssistPlan.Kind.SLIME,
                "a carried valid aid precedes a hypothetical AE water bucket");
        field(CalculationContext.class, "automaticLandingSupply").setBoolean(context, false);
        check(fall(context, 6, result), "renewed carried materials remain usable after a failed supply attempt");
        inventory(context, Set.of());
        check(!fall(context, 3, result) && result.cost < ActionCosts.COST_INF,
                "a harmless three-block descent still needs no aid or supply request");

        field(CalculationContext.class, "automaticLandingSupply").setBoolean(context, true);
        var grassFeet = new BlockPos(1,0,0);
        var fast = new WaterLandingWindow(0.08,4.5,1.62,2.603278959908224);
        field(CalculationContext.class, "waterLandingWindow").set(context,fast);
        f.world.scene.blocks.put(grassFeet,Blocks.TALL_GRASS.defaultBlockState());
        f.world.scene.blocks.put(grassFeet.above(),Blocks.TALL_GRASS.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        check(!fast.permits(28) && context.landingPlans(grassFeet,28).stream().anyMatch(plan -> plan.kind() == LandingAssistPlan.Kind.WATER),
                "planned bucket window uses the elevated grass hit face instead of the dry ground height");
        f.world.scene.blocks.remove(grassFeet); f.world.scene.blocks.remove(grassFeet.above());
        field(CalculationContext.class, "waterLandingWindow").set(context,new WaterLandingWindow(0.08,4.5,1.62,0));
        field(CalculationContext.class, "maicraftPolicy").set(context,
                EmbeddedBaritonePolicy.capture(null, LongSets.singleton(new BlockPos(1, 0, 0).asLong()), null));
        check(!fall(context, 6, result) && result.cost == ActionCosts.COST_INF,
                "self-rescue supply does not bypass protected placement geometry");
    }

    private static void inventory(CalculationContext context, Set<LandingAssistPlan.Kind> kinds) throws Exception {
        field(CalculationContext.class, "landingInventory").set(context,
                new LandingAssistPlan.InventorySnapshot(kinds, true, true, false));
    }
    private static boolean fall(CalculationContext context, int height, MutableMoveResult result) {
        result.reset();
        return MovementDescend.dynamicFallCost(context, 0, height, 0, 1, 0, 0,
                Blocks.AIR.defaultBlockState(), result);
    }
    private static final class BlocksView extends BlockStateInterface {
        BlockGetter world;
        private BlocksView() { super(null); }
        public boolean worldContainsLoadedChunk(int x, int z) { return true; }
        public BlockState get0(int x, int y, int z) { return world.getBlockState(new BlockPos(x, y, z)); }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
