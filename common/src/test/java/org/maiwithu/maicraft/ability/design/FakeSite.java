// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 读现场的替身：一张世界表，摆了的格算加载，没摆的不算；看得见的格、自己放的格各一份名单；
 * 天然地形是空气、草方块、泥土和一放就顶掉的。
 */
final class FakeSite implements ReadsSiteCells {

    static final String DIM = "minecraft:overworld";

    final Map<BlockPos, BlockState> world = new HashMap<>();
    final Set<BlockPos> hiddenFromEye = new HashSet<>();
    final Set<BlockPos> selfPlaced = new HashSet<>();

    /**
     * 一间小木屋：x、z 从 0 到 4，地板 y=63，墙 y=64..66，屋顶 y=67；屋外是草地（y=63），底下一层泥土（y=62）。
     * 北墙中间 (2,64,0) 装一扇朝南的门（开着），屋里 (3,64,1) 放一张床（朝南，床头 (3,64,2)）。
     * 屋里的格从屋外看不见；周围一圈空气到 y=69。
     */
    static FakeSite cottage() {
        FakeSite site = new FakeSite();
        for (int x = -3; x <= 7; x++) {
            for (int z = -3; z <= 7; z++) {
                site.world.put(new BlockPos(x, 62, z), Blocks.DIRT.defaultBlockState());
                for (int y = 63; y <= 69; y++) {
                    boolean inside = x >= 0 && x <= 4 && z >= 0 && z <= 4;
                    boolean shell = x == 0 || x == 4 || z == 0 || z == 4;
                    BlockState state = Blocks.AIR.defaultBlockState();
                    if (y == 63) state = inside ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                    if (y >= 64 && y <= 66 && inside && shell) state = Blocks.SPRUCE_PLANKS.defaultBlockState();
                    if (y == 67 && inside) state = Blocks.SPRUCE_PLANKS.defaultBlockState();
                    site.world.put(new BlockPos(x, y, z), state);
                    if (inside && !shell && y >= 64 && y <= 66) site.hiddenFromEye.add(new BlockPos(x, y, z));
                }
            }
        }
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.OPEN, true);
        site.world.put(new BlockPos(2, 64, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        site.world.put(new BlockPos(2, 65, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        site.world.put(new BlockPos(3, 64, 1), bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
        site.world.put(new BlockPos(3, 64, 2), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        return site;
    }

    @Override public String dimension() {
        return DIM;
    }

    @Override public boolean loaded(BlockPos pos) {
        return world.containsKey(pos);
    }

    @Override public BlockState state(BlockPos pos) {
        return world.get(pos);
    }

    @Override public boolean visible(BlockPos pos) {
        return !hiddenFromEye.contains(pos);
    }

    @Override public boolean selfPlaced(BlockPos pos) {
        return selfPlaced.contains(pos);
    }

    @Override public boolean natural(BlockPos pos) {
        BlockState state = world.get(pos);
        return state == null || state.isAir() || state.canBeReplaced() || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT);
    }
}
