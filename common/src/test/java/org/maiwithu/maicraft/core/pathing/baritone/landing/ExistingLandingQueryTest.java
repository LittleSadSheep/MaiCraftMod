package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import sun.misc.Unsafe;

/** 下落柱只问已有缓冲物时跳过新放几何；真正落点仍检查材料候选、身体碰撞与伤害。 */
public final class ExistingLandingQueryTest {
    private static final BlockPos FEET = new BlockPos(4, 1, 4);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        var memory = (Unsafe) memoryField.get(null);
        var scene = new Scene();
        var blocks = (BlocksView) memory.allocateInstance(BlocksView.class);
        blocks.loaded = true;
        set(BlockStateInterface.class, blocks, "access", scene);
        var context = (CalculationContext) memory.allocateInstance(CalculationContext.class);
        set(CalculationContext.class, context, "bsi", blocks);
        set(CalculationContext.class, context, "automaticLandingSupply", true);
        set(CalculationContext.class, context, "fallDamageBudget",
                new FallDamageBudget(1, 0, 3, 1, 0, 0, 0, 0, .08, 0, false));
        set(CalculationContext.class, context, "waterLandingWindow", new WaterLandingWindow(.08, 4.5, 1.62, 0));
        policy(context, false);
        inventory(context, Set.of());
        skipsUnusedGeometry(context, scene);
        preservesExistingEvidence(context, scene);
        blocks.loaded = false; scene.reads = 0;
        check(context.existingLandingPlans(FEET).isEmpty() && scene.reads == 0,
                "an unloaded landing column is rejected before reading block geometry");
        System.out.println("ExistingLandingQueryTest: passed");
    }

    private static void skipsUnusedGeometry(CalculationContext context, Scene scene) throws Exception {
        // 背包没有救援物也不能伪造库存；沿干燥落柱查询已有物时，不扫描身体外围的落地碰撞。
        check(context.existingLandingPlans(FEET).isEmpty() && scene.lateralReads == 0,
                "empty inventory with automatic supply enabled avoids all new-aid body geometry");
        check(context.landingPlans(FEET).stream().anyMatch(plan -> !plan.existing()) && scene.lateralReads > 0,
                "the actual final-landing query still geometrically checks conditional protection");
        check(context.landingInventory.available().isEmpty(), "conditional plans do not become carried inventory");
        // 已带黏液块时也一样：这里只判断现场已有缓冲，不能先证明一个稍后根本不用的新放平台。
        inventory(context, Set.of(LandingAssistPlan.Kind.SLIME)); scene.lateralReads = 0;
        check(context.existingLandingPlans(FEET).isEmpty() && scene.lateralReads == 0,
                "carried new support is filtered before the expensive native landing inspection");
        inventory(context, Set.of());
    }

    private static void preservesExistingEvidence(CalculationContext context, Scene scene) throws Exception {
        // 没带水桶仍可落入现场水源；缩小查询范围不能取消已有水的身体空间证明。
        scene.blocks.put(FEET, Blocks.WATER.defaultBlockState()); scene.lateralReads = 0;
        check(context.existingLandingPlans(FEET, 24).stream().anyMatch(plan -> plan.kind() == LandingAssistPlan.Kind.WATER)
                        && scene.lateralReads > 0,
                "existing water remains usable without a carried bucket and still receives full geometry validation");
        scene.blocks.put(FEET.above(), Blocks.STONE.defaultBlockState());
        check(context.existingLandingPlans(FEET).isEmpty(), "a new ceiling invalidates the existing water landing");
        scene.blocks.remove(FEET.above()); policy(context, true);
        check(context.existingLandingPlans(FEET).isEmpty(), "body protection still rejects an existing water landing");
        policy(context, false); scene.blocks.clear();
        scene.blocks.put(FEET.below(), Blocks.HAY_BLOCK.defaultBlockState());
        // 同一干草落点要按每次落差重算伤害，低血量不能复用上一段短落差的许可。
        check(context.existingLandingPlans(FEET, 3).stream().anyMatch(plan -> plan.kind() == LandingAssistPlan.Kind.HAY),
                "a harmless short drop may use existing hay");
        check(context.existingLandingPlans(FEET, 9).isEmpty(), "a lethal taller drop is rejected at the same hay position");
    }

    private static void inventory(CalculationContext context, Set<LandingAssistPlan.Kind> kinds) throws Exception {
        set(CalculationContext.class, context, "landingInventory", new LandingAssistPlan.InventorySnapshot(kinds, true, true, false));
    }
    private static void policy(CalculationContext context, boolean forbidden) throws Exception {
        set(CalculationContext.class, context, "maicraftPolicy", EmbeddedBaritonePolicy.capture(null, null,
                forbidden ? LongSets.singleton(FEET.asLong()) : LongSets.emptySet()));
    }
    private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static final class BlocksView extends BlockStateInterface {
        boolean loaded;
        private BlocksView() { super(null); }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return loaded; }
    }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int reads, lateralReads;
        public BlockState getBlockState(BlockPos position) {
            reads++;
            if (position.getX() != FEET.getX() || position.getZ() != FEET.getZ()) lateralReads++;
            return blocks.getOrDefault(position, (position.getY() == 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos position) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
