package org.maiwithu.maicraft.core.pathing.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class ClientSurfaceHeightTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.LOGS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.BIRCH_LOG)),
                BlockTags.LEAVES, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.BIRCH_LEAVES))));
        Column world = new Column();
        world.blocks.put(63, Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 64; y < 69; y++) world.blocks.put(y, Blocks.BIRCH_LOG.defaultBlockState());
        world.blocks.put(69, Blocks.BIRCH_LEAVES.defaultBlockState());
        check(ClientSurfaceHeight.constructionGround(world, 0, 0, 70) == 64,
                "a birch tree must expose the ground for a clearing job");
        world.blocks.put(68, Blocks.WATER.defaultBlockState());
        check(ClientSurfaceHeight.constructionGround(world, 0, 0, 70) == 69,
                "water must remain visible to the site's fluid rejection");
        world.blocks.put(68, Blocks.CHEST.defaultBlockState());
        check(ClientSurfaceHeight.constructionGround(world, 0, 0, 70) == 69,
                "construction and containers must not be skipped as vegetation");
        world.blocks.clear();
        world.reads = 0;
        check(ClientSurfaceHeight.constructionGround(world, 0, 0, 200) == -64,
                "a column with no bounded ground must be rejected");
        check(world.reads == 64, "a floating canopy must not scan the world height");
        System.out.println("ClientSurfaceHeightTest: passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class Column implements BlockGetter {
        final Map<Integer, BlockState> blocks = new HashMap<>();
        int reads;
        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            return blocks.getOrDefault(pos.getY(), Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
