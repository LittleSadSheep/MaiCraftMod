package org.maiwithu.maicraft.core.task.explore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreCompanionTask.FluidColumn;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreCompanionTask.SurfaceKind;

/** 顶块流体分类：熔岩列进地形要素记忆，水列照旧可游泳通过，两者都不是移动落脚格。 */
public final class SurfaceColumnClassificationTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 无游戏的Bootstrap不会加载原版数据包；按仓库先例手工绑定水与熔岩标签，结束后恢复，不污染同进程其他回归。
        var fluids = BuiltInRegistries.FLUID;
        Map<TagKey<Fluid>, List<Holder<Fluid>>> previous = new HashMap<>();
        fluids.getTags().forEach(pair -> previous.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var bound = new HashMap<>(previous);
        bound.put(FluidTags.WATER, List.of(fluids.wrapAsHolder(Fluids.WATER), fluids.wrapAsHolder(Fluids.FLOWING_WATER)));
        bound.put(FluidTags.LAVA, List.of(fluids.wrapAsHolder(Fluids.LAVA), fluids.wrapAsHolder(Fluids.FLOWING_LAVA)));
        fluids.bindTags(bound);
        try {
            classifyColumns();
        } finally {
            fluids.bindTags(previous);
        }
    }
    private static void classifyColumns() {

        // 三格深熔岩湖：顶块 y=7，y=5..7 是熔岩，y=4 起是石头底。
        FakeColumn lava = new FakeColumn();
        lava.fill(Blocks.LAVA, 5, 7);
        lava.fill(Blocks.STONE, 0, 4);
        FluidColumn lavaColumn = SemanticExploreCompanionTask.fluidColumn(
                lava.state(7).getFluidState(), lava, 0, 0, new BlockPos(0, 7, 0), 0);
        check(lavaColumn != null && lavaColumn.kind() == SurfaceKind.LAVA && lavaColumn.depth() == 3,
                "lava surface classifies as LAVA with per-column depth");

        // 深熔岩柱按既有水深口径封顶 8，不随列深无限增长。
        FakeColumn deepLava = new FakeColumn();
        deepLava.fill(Blocks.LAVA, 0, 15);
        FluidColumn capped = SemanticExploreCompanionTask.fluidColumn(
                deepLava.state(15).getFluidState(), deepLava, 0, 0, new BlockPos(0, 15, 0), 0);
        check(capped != null && capped.kind() == SurfaceKind.LAVA && capped.depth() == 8,
                "lava depth is capped at eight like water depth");

        // 既有水面行为不变：顶块是水仍分类为 WATER。
        FakeColumn water = new FakeColumn();
        water.fill(Blocks.WATER, 6, 7);
        water.fill(Blocks.STONE, 0, 5);
        FluidColumn waterColumn = SemanticExploreCompanionTask.fluidColumn(
                water.state(7).getFluidState(), water, 0, 0, new BlockPos(0, 7, 0), 0);
        check(waterColumn != null && waterColumn.kind() == SurfaceKind.WATER && waterColumn.depth() == 2,
                "water surface keeps the legacy WATER classification");

        // 干地表顶面没有流体，返回 null 交回干立足点判定，不冒充任何流体分类。
        FakeColumn dry = new FakeColumn();
        dry.fill(Blocks.GRASS_BLOCK, 0, 7);
        check(SemanticExploreCompanionTask.fluidColumn(
                dry.state(7).getFluidState(), dry, 0, 0, new BlockPos(0, 7, 0), 0) == null,
                "dry surface returns no fluid column");
        System.out.println("SurfaceColumnClassificationTest: passed");
    }

    /** 单列方块世界：只有 x=z=0 一列有方块，供建立在真实 BlockState 上的静态分类器直测。 */
    private static final class FakeColumn implements BlockGetter {
        private final Map<Integer, BlockState> cells = new HashMap<>();
        void fill(Block block, int fromY, int toY) {
            for (int y = fromY; y <= toY; y++) cells.put(y, block.defaultBlockState());
        }
        BlockState state(int y) {
            return cells.getOrDefault(y, Blocks.AIR.defaultBlockState());
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.getX() != 0 || pos.getZ() != 0) throw new AssertionError("read outside the test column");
            return state(pos.getY());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }
        @Override public int getHeight() { return 16; }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getMaxBuildHeight() { return 16; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
