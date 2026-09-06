package org.maiwithu.maicraft.core.task.mine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Source evidence used by semantic material mining, without a game or machine-ID exclusions. */
public final class NaturalTreeSourceTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        Map<TagKey<Block>, List<Holder<Block>>> originalTags = new HashMap<>(tags);
        tags.put(BlockTags.LOGS, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder()));
        tags.put(BlockTags.DIRT, List.of(Blocks.DIRT.builtInRegistryHolder(), Blocks.GRASS_BLOCK.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try {
        BlockPos root = new BlockPos(0, 64, 0);
        Scene tree = new Scene(); tree.grow(root);
        NaturalTreeSource observed = new NaturalTreeSource(); observed.beginQuery();
        check(observed.accepts(root.above(2), tree, pos -> true), "rooted connected trunk and natural crown");
        int reads = tree.reads;
        tree.blocks.put(root, Blocks.AIR.defaultBlockState());
        check(observed.accepts(root.above(3), tree, pos -> true) && tree.reads == reads,
                "harvesting the verified trunk reset source evidence or rescanned every tick");
        Scene pole = new Scene(); pole.grow(root);
        pole.blocks.entrySet().removeIf(entry -> entry.getValue().getBlock() instanceof LeavesBlock);
        check(!accepts(root, pole), "bare log pole was a natural source");
        pole.blocks.put(root.above(4).east(), leaf());
        check(!accepts(root, pole), "one nearby natural leaf authorized a pole");
        Scene persistent = new Scene(); persistent.grow(root);
        persistent.blocks.replaceAll((pos, state) -> state.getBlock() instanceof LeavesBlock
                ? state.setValue(LeavesBlock.PERSISTENT, true) : state);
        check(!accepts(root, persistent), "persistent decorative foliage was a natural crown");
        Scene foundation = new Scene(); foundation.grow(root);
        foundation.blocks.put(root.below(), Blocks.STONE_BRICKS.defaultBlockState());
        check(!accepts(root, foundation), "a constructed foundation was accepted as rooted terrain");
        Scene wired = new Scene(); wired.grow(root);
        wired.blocks.put(root.above(2).east(), Blocks.OAK_SIGN.defaultBlockState());
        check(!accepts(root, wired), "a wired pole became natural because it had a complete nearby crown");
        Scene ladder = new Scene(); ladder.grow(root);
        ladder.blocks.put(root.above(2).east(), Blocks.LADDER.defaultBlockState());
        check(!accepts(root, ladder), "a constructed trunk attachment was ignored");
        Scene beam = new Scene(); beam.grow(root);
        beam.blocks.put(root.above(2), Blocks.BIRCH_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        check(!accepts(root.above(2), beam), "horizontal timber was treated as upright trunk");
        Scene complete = new Scene(); complete.grow(root);
        var missing = new NaturalTreeSource(); missing.beginQuery();
        check(!missing.accepts(root, complete, pos -> !pos.equals(root.below())) && missing.deferred
                        && missing.rejected.isEmpty(), "unloaded evidence was permanently rejected or accepted");
        missing.beginQuery();
        check(missing.accepts(root, complete, pos -> true), "loaded evidence did not resume");
        Scene forest = new Scene();
        var budget = new NaturalTreeSource(); budget.beginQuery();
        for (int i = 0; i < 9; i++) {
            BlockPos next = root.east(i * 8); forest.grow(next);
            check(budget.accepts(next, forest, pos -> true) == (i < 8), "one query exceeded its tree observation budget");
        }
        check(budget.deferred && forest.reads < 4096, "source inspection performed an unbounded scan");
        budget.beginQuery();
        check(budget.accepts(root.east(64), forest, pos -> true), "deferred candidate was lost");
        } finally {
            BuiltInRegistries.BLOCK.bindTags(originalTags);
        }
        System.out.println("NaturalTreeSourceTest: passed");
    }

    private static boolean accepts(BlockPos root, Scene scene) {
        var source = new NaturalTreeSource(); source.beginQuery(); return source.accepts(root, scene, pos -> true);
    }
    private static BlockState leaf() { return Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int reads;
        void grow(BlockPos root) {
            blocks.put(root.below(), Blocks.DIRT.defaultBlockState());
            for (int y = 0; y < 5; y++) blocks.put(root.above(y), Blocks.BIRCH_LOG.defaultBlockState());
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) blocks.put(root.above(4).offset(x, 0, z), leaf());
            }
        }
        public BlockState getBlockState(BlockPos pos) { reads++; return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("source scan must not read live block entities"); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
