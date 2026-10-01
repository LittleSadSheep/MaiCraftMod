// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.intent.SemanticResultView;

/** 先看现场再选配方：木梁不能抢过野生树，观察不加载区块也不增加材料库存。 */
public final class NearbyMaterialSourcesTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<TagKey<Block>, List<Holder<Block>>> original = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>(original);
        tags.put(BlockTags.LOGS, List.of(Blocks.OAK_LOG.builtInRegistryHolder(), Blocks.BIRCH_LOG.builtInRegistryHolder()));
        tags.put(BlockTags.DIRT, List.of(Blocks.DIRT.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try {
            Scene scene = new Scene();
            BlockPos center = new BlockPos(0, 64, 0), birch = center.east(5), oak = center.west(3);
            scene.grow(birch, Blocks.BIRCH_LOG);
            scene.grow(oak, Blocks.OAK_LOG);
            // 橡木树干挂有梯子，属于已有构筑物；较远的白桦仍应成为可自动取材的线索。
            scene.blocks.put(oak.above(2).north(), Blocks.LADDER.defaultBlockState());
            scene.blocks.put(center.south(2), Blocks.IRON_ORE.defaultBlockState());
            scene.blocks.put(center.south(12), Blocks.COAL_ORE.defaultBlockState());
            var sources = new NearbyMaterialSources(scene, pos -> true, pos -> false, center, 8,
                    Set.of(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.IRON_ORE, Blocks.COAL_ORE));
            finish(sources);
            check(sources.nearest(Set.of(Blocks.OAK_LOG)).isEmpty(), "建筑原木不能成为野生来源");
            check(sources.nearest(Set.of(Blocks.BIRCH_LOG)).orElseThrow().position().equals(birch), "应保留最近的天然白桦树干");
            check(sources.nearest(Set.of(Blocks.IRON_ORE)).isPresent(), "原木以外的已知矿物来源也能提供排序线索");
            check(sources.nearest(Set.of(Blocks.COAL_ORE)).isEmpty(), "不能把搜索范围外的材料带入备料计划");
            check(sources.describe().get("inventory_credit").equals(0), "看到矿石和树不代表已经取得材料");
            // 已观察的位置被采走后应立即失去优先依据，由下一轮扫描寻找剩余来源。
            scene.blocks.remove(birch);
            check(sources.changed() && sources.nearest(Set.of(Blocks.BIRCH_LOG)).isEmpty(), "失效位置不能继续参与排序");
            scene.grow(birch, Blocks.BIRCH_LOG);
            var protectedTree = new NearbyMaterialSources(scene, pos -> true,
                    pos -> pos.getX() == birch.getX() && pos.getZ() == birch.getZ(), center, 8, Set.of(Blocks.BIRCH_LOG));
            finish(protectedTree);
            check(protectedTree.nearest(Set.of(Blocks.BIRCH_LOG)).isEmpty(), "保护范围内的树不能获得取材优先级");
            // 缺少树根所在区块时保留不确定证据；扫描完成只表示已加载范围处理完毕。
            var incomplete = new NearbyMaterialSources(scene, pos -> !pos.equals(birch.below()),
                    pos -> false, center, 8, Set.of(Blocks.BIRCH_LOG));
            finish(incomplete);
            check(incomplete.nearest(Set.of(Blocks.BIRCH_LOG)).isEmpty()
                    && Boolean.TRUE.equals(incomplete.describe().get("unloaded_tree_evidence")), "未加载树根不能被推断为天然树");
            // 未加载范围会影响选料判断，经过默认语义回执整理后也必须保留，不能被当成内部几何删掉。
            var publicEvidence = SemanticResultView.data(Map.of("nearby_material_sources", incomplete.describe()));
            var publicScan = (Map<?, ?>) publicEvidence.get("nearby_material_sources");
            check(((Number) publicScan.get("unloaded_cell_count")).intValue() > 0
                    && publicScan.containsKey("inspected_cell_count"), "默认回执必须保留来源扫描的不确定范围");
            check(scene.blockEntityReads == 0, "来源观察不打开箱子或读取机器库存");
        } finally {
            BuiltInRegistries.BLOCK.bindTags(original);
        }
        System.out.println("NearbyMaterialSourcesTest: passed");
    }

    private static void finish(NearbyMaterialSources sources) {
        // 离线推进多个游戏刻，核对扫描会续上游标而不是预算用尽就漏掉后半区域。
        for (int tick = 0; tick < 1000 && !sources.complete(); tick++) sources.advance();
        check(sources.complete(), "有限附近范围必须能够扫描完成");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int blockEntityReads;
        void grow(BlockPos root, Block log) {
            // 三格以上直立树干、泥土根部和非持久树冠对应生产代码使用的同一组天然树证据。
            blocks.put(root.below(), Blocks.DIRT.defaultBlockState());
            for (int y = 0; y < 5; y++) blocks.put(root.above(y), log.defaultBlockState());
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                if (x != 0 || z != 0) blocks.put(root.above(4).offset(x, 0, z),
                        Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
        }
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { blockEntityReads++; return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
