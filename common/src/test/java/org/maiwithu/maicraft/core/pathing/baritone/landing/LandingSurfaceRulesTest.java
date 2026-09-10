package org.maiwithu.maicraft.core.pathing.baritone.landing;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall;

/**
 * 逐项检查草、半砖和楼梯上的落地辅助几何，包含实际含水与舀水方法；确认保护区也覆盖可能间接受影响的高草另一半。
 */
public final class LandingSurfaceRulesTest {
    private static final BlockPos FEET = new BlockPos(0,1,0);
    private static final LandingAssistPlan.InventorySnapshot WATER =
            new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.WATER),true,false,false);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var tags = Holder.Reference.class.getDeclaredField("tags"); tags.setAccessible(true);
        Object webHolder = Blocks.COBWEB.builtInRegistryHolder(), saved = tags.get(webHolder);
        try {
            tags.set(webHolder,Set.of(BlockTags.FALL_DAMAGE_RESETTING));
            vegetation(); waterloggedSupport(); nativeBlockPlacement();
        } finally { tags.set(webHolder,saved); }
        System.out.println("LandingSurfaceRulesTest: passed");
    }

    private static void vegetation() {
        for (Block plant : new Block[]{Blocks.SHORT_GRASS,Blocks.FERN,Blocks.TALL_GRASS,Blocks.LARGE_FERN}) {
            var scene = new Scene(); BlockState lower = plant.defaultBlockState();
            boolean tall = lower.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
            scene.blocks.put(FEET,lower);
            if (tall) scene.blocks.put(FEET.above(),lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
            var plan = water(scene);
            BlockPos top = tall ? FEET.above() : FEET;
            check(plan.feet().equals(FEET) && (plan.cell().equals(FEET) || plan.cell().equals(top.above())),
                    "visible floor permits replacing the plant cell; a blocked floor retains the native upper-source option");
            Vec3 eye = Vec3.atBottomCenterOf(FEET.above(5));
            var hit = scene.clip(new ClipContext(eye,plan.aimPoint().add(plan.aimPoint().subtract(eye).normalize().scale(.01)),ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,CollisionContext.empty()));
            check(WaterBucketFall.waterCell(scene,hit,false).equals(plan.cell()),
                    "actual native bucket ray and planned water cell agree over vegetation");
            check(safe(scene,plan),"non-colliding vegetation leaves real ground and whole-body clearance");
            check(WATER.plans(scene,FEET,pos -> pos.equals(FEET)).isEmpty(),"protected lower plants remain protected");
            if (tall) check(WATER.plans(scene,FEET,pos -> pos.equals(FEET.above())).isEmpty(),
                    "a protected paired upper plant cannot be removed indirectly");
            scene.blocks.put(plan.cell(),Blocks.WATER.defaultBlockState());
            check(LandingAssistPlan.existingSafe(LandingAssistPlan.Kind.WATER,scene.getBlockState(plan.cell()))
                            && (plan.cell().equals(FEET) || !LandingAssistPlan.existingSafe(LandingAssistPlan.Kind.WATER,scene.getBlockState(FEET))),
                    "source evidence belongs to the actual replacement cell");
            check(water(scene).existing(),"an observed high source is usable without claiming placement ownership");
            check(LandingAssistGeometry.safeAfterRemoval(scene,pos -> true,plan,.6,1.8,LongSets.emptySet()),
                    "source recovery retains independently verified real ground");
            if (tall) {
                var nativeLowerAfter = lower.updateShape(Direction.UP,Blocks.WATER.defaultBlockState(),null,FEET,FEET.above());
                check(nativeLowerAfter.isAir(),"native replacement of the upper half removes the lower half, not restores it");
            }
        }
        var blocked = new Scene(); blocked.blocks.put(FEET,Blocks.STONE.defaultBlockState());
        check(WATER.plans(blocked,FEET,pos -> false).isEmpty(),"a non-replaceable building block is not admitted as vegetation");
    }

    private static void waterloggedSupport() {
        for (SlabType type : SlabType.values()) {
            var scene = new Scene();
            scene.blocks.put(FEET.below(),Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,type));
            var plans = WATER.plans(scene,FEET,pos -> false);
            if (type == SlabType.DOUBLE) {
                check(plans.getFirst().cell().equals(FEET) && safe(scene,plans.getFirst()),
                        "double slab rejects waterlogging, so the native fallback fills the air above its full floor");
                continue;
            }
            var plan = plans.getFirst();
            check(plan.cell().equals(FEET.below()) && plan.clicked().equals(plan.cell()),
                    "slab water goes into the actual clicked container");
            check(safe(scene,plan) == (type == SlabType.BOTTOM),
                    "only fluid extending above the actual slab floor offers pre-impact water contact");
            nativeFillAndPickup(scene,plan.cell());
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) for (Half half : Half.values()) for (StairsShape shape : StairsShape.values()) {
            var scene = new Scene();
            scene.blocks.put(FEET.below(),Blocks.STONE_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING,facing).setValue(BlockStateProperties.HALF,half)
                    .setValue(BlockStateProperties.STAIRS_SHAPE,shape));
            var plan = water(scene);
            check(!safe(scene,plan),"centered body on a top-height stair step is not cushioned by water below its feet");
            nativeFillAndPickup(scene,plan.cell());
            check(LandingAssistPlan.canPlace(LandingAssistPlan.Kind.COBWEB,scene,FEET)
                            && safe(scene,new LandingAssistPlan(LandingAssistPlan.Kind.COBWEB,FEET,FEET,FEET.below(),Direction.UP,false)),
                    "stairs retain a valid damage-free block alternative instead of an artificial full-face rejection");
        }
    }

    private static void nativeBlockPlacement() {
        var scene = new Scene(); scene.blocks.put(FEET.below(),Blocks.STONE_SLAB.defaultBlockState());
        for (var kind : new LandingAssistPlan.Kind[]{LandingAssistPlan.Kind.COBWEB,LandingAssistPlan.Kind.SLIME,LandingAssistPlan.Kind.HAY}) {
            check(LandingAssistPlan.canPlace(kind,scene,FEET),"native ordinary blocks do not require a full sturdy placement face");
            scene.blocks.put(FEET,Blocks.SHORT_GRASS.defaultBlockState());
            check(LandingAssistPlan.canPlace(kind,scene,FEET),"replaceable grass does not create an air-only block-item gate");
            scene.blocks.remove(FEET);
        }
    }

    // 调用方块本身的装水和舀水实现，确认含水支撑恢复成原来的干燥状态，不把整块方块删掉。
    private static void nativeFillAndPickup(Scene scene, BlockPos position) {
        BlockState dry = scene.getBlockState(position);
        LevelAccessor level = (LevelAccessor)Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
                new Class<?>[]{LevelAccessor.class},(proxy,method,args) -> switch (method.getName()) {
                    case "isClientSide" -> false;
                    case "getBlockState" -> scene.getBlockState((BlockPos)args[0]);
                    case "setBlock" -> { scene.blocks.put((BlockPos)args[0],(BlockState)args[1]); yield true; }
                    case "scheduleTick" -> null;
                    default -> throw new AssertionError("unexpected native waterlogging operation: " + method.getName());
                });
        check(((LiquidBlockContainer)dry.getBlock()).placeLiquid(level,position,dry,Fluids.WATER.getSource(false)),
                "real native container placement accepts water");
        BlockState wet = scene.getBlockState(position);
        check(WaterBucketFall.sourceWater(wet) && WaterBucketFall.canRecover(wet,true,false)
                        && !WaterBucketFall.canRecover(wet,false,false),"source ownership is still required for waterlogged recovery");
        check(WaterBucketFall.dryGeometry(wet).equals(dry),"geometry never deletes the solid waterlogged support");
        check(((BucketPickup)wet.getBlock()).pickupBlock(null,level,position,wet).is(net.minecraft.world.item.Items.WATER_BUCKET)
                        && scene.getBlockState(position).equals(dry),"native pickup restores the original dry support state");
    }
    private static LandingAssistPlan water(Scene scene) { return WATER.plans(scene,FEET,pos -> false).getFirst(); }
    private static boolean safe(Scene scene,LandingAssistPlan plan) {
        return LandingAssistGeometry.safe(scene,pos -> true,plan,.6,1.8,LongSets.emptySet());
    }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos,BlockState> blocks = new HashMap<>();
        public BlockState getBlockState(BlockPos position) {
            return blocks.getOrDefault(position,(position.getY() == 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos position) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
