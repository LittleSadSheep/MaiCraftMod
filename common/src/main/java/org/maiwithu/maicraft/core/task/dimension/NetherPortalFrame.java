// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

/** 已观察或拟建的原版传送门框；原点为内侧左下格，四角方块可有可无。 */
public record NetherPortalFrame(BlockPos origin, Direction.Axis axis, int width, int height) {
    public NetherPortalFrame {
        origin = origin.immutable();
        if (axis == Direction.Axis.Y || width < 2 || width > 21 || height < 3 || height > 21)
            throw new IllegalArgumentException("invalid vanilla Nether portal dimensions");
    }

    public BlockPos cell(int across, int up) {
        return origin.relative(axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH, across).above(up);
    }

    public List<BlockPos> frame() {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < width; x++) { cells.add(cell(x, -1)); cells.add(cell(x, height)); }
        for (int y = 0; y < height; y++) { cells.add(cell(-1, y)); cells.add(cell(width, y)); }
        return List.copyOf(cells);
    }

    public List<BlockPos> interior() {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) cells.add(cell(x, y));
        return List.copyOf(cells);
    }

    public List<BlockPos> missing(Function<BlockPos, BlockState> read) {
        return frame().stream().filter(p -> !isObsidian(read.apply(p))).toList();
    }

    public boolean ready(Function<BlockPos, BlockState> read) {
        return frame().stream().allMatch(p -> isObsidian(read.apply(p)))
                && interior().stream().allMatch(p -> empty(read.apply(p)));
    }

    public boolean active(Function<BlockPos, BlockState> read) {
        return interior().stream().allMatch(p -> {
            BlockState state = read.apply(p);
            return state != null && state.is(Blocks.NETHER_PORTAL)
                    && state.getValue(NetherPortalBlock.AXIS) == axis;
        });
    }

    /** null 表示该格尚未加载，绝不能作为世界为空的证据。 */
    public static boolean empty(BlockState state) {
        return state != null && (state.isAir() || state.is(Blocks.FIRE) || state.is(Blocks.NETHER_PORTAL));
    }

    private static boolean isObsidian(BlockState state) { return state != null && state.is(Blocks.OBSIDIAN); }

    /** 从框内格子反推出完整门框边界；读取范围有界且只检查已加载地形。 */
    public static NetherPortalFrame observe(Function<BlockPos, BlockState> read, BlockPos seed, Direction.Axis axis) {
        if (axis == Direction.Axis.Y || !empty(read.apply(seed))) return null;
        BlockPos bottom = seed;
        for (int i = 0; i < 21 && empty(read.apply(bottom.below())); i++) bottom = bottom.below();
        Direction across = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        for (int i = 0; i < 21 && empty(read.apply(bottom.relative(across, -1))); i++)
            bottom = bottom.relative(across, -1);
        int width = 0;
        while (width <= 21 && empty(read.apply(bottom.relative(across, width)))) width++;
        int height = 0;
        while (height <= 21 && empty(read.apply(bottom.above(height)))) height++;
        if (width < 2 || width > 21 || height < 3 || height > 21) return null;
        var frame = new NetherPortalFrame(bottom, axis, width, height);
        return frame.ready(read) ? frame : null;
    }
}
