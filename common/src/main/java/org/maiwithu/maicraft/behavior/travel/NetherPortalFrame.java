// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一扇下界传送门的门框：原点是内侧左下格，横向沿轴铺开、竖向向上；四角的黑曜石可有可无。
 *
 * <p>原版下界门框内侧宽二到二十一格、高三到二十一格；门框竖着一圈黑曜石，
 * 内侧出现下界传送门方块才算这扇门是活的。勘察补框与建门验活都用这里的几何：
 * 从门内一格反推出整个门框，数出缺的黑曜石；点火之后按内侧的门方块确认门成型了。
 */
public record NetherPortalFrame(BlockPos origin, Direction.Axis axis, int width, int height) {

    /** 原版允许的门框尺寸：内侧最窄两格宽三格高，最宽最高二十一格。 */
    private static final int MIN_WIDTH = 2;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX_SIZE = 21;

    public NetherPortalFrame {
        origin = origin.immutable();
        if (axis == Direction.Axis.Y || axis == null) {
            throw new IllegalArgumentException("下界门是立着的：轴取 X 或 Z");
        }
        if (width < MIN_WIDTH || width > MAX_SIZE || height < MIN_HEIGHT || height > MAX_SIZE) {
            throw new IllegalArgumentException("不是原版尺寸的下界门框：" + width + "×" + height);
        }
    }

    /** 门内一格往哪个方向横着铺：轴是 X 就朝东，轴是 Z 就朝南。 */
    private Direction across() {
        return axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
    }

    /** 门框里横向第 {@code across} 格、竖向第 {@code up} 格（相对原点，原点是 (0, 0)）。 */
    public BlockPos cell(int across, int up) {
        return origin.relative(across(), across).above(up);
    }

    /**
     * 门框必须有黑曜石的格：底一行、顶一行、左右两列，不含四角——原版点火不看四角，
     * 玩家摆框省四块、浇筑也只浇十格，验框与补框都不把四角算成缺的。
     */
    public List<BlockPos> frame() {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            cells.add(cell(x, -1));
            cells.add(cell(x, height));
        }
        for (int y = 0; y < height; y++) {
            cells.add(cell(-1, y));
            cells.add(cell(width, y));
        }
        return List.copyOf(cells);
    }

    /** 门内侧的全部格：点火后门方块就该长在这里。 */
    public List<BlockPos> interior() {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cells.add(cell(x, y));
            }
        }
        return List.copyOf(cells);
    }

    /** 这一格该放黑曜石但还不是黑曜石：补框蓝图就由这些格编成。 */
    public List<BlockPos> missing(Function<BlockPos, Optional<BlockState>> read) {
        return frame().stream().filter(cell -> !isObsidian(read, cell)).toList();
    }

    /** 门框齐了、内侧也空着：可以点火的状态。 */
    public boolean ready(Function<BlockPos, Optional<BlockState>> read) {
        return frame().stream().allMatch(cell -> isObsidian(read, cell))
                && interior().stream().allMatch(cell -> empty(read, cell));
    }

    /** 门内侧已经长满门方块（轴也对得上）：这扇门活了。 */
    public boolean active(Function<BlockPos, Optional<BlockState>> read) {
        return interior().stream().allMatch(cell -> {
            Optional<BlockState> state = read.apply(cell);
            return state.isPresent() && state.get().is(Blocks.NETHER_PORTAL)
                    && state.get().getValue(NetherPortalBlock.AXIS) == axis;
        });
    }

    /** 门内侧算"空"的方块：空气、火（点火那一刻就是火）、或已经是门方块。 */
    private static boolean empty(Function<BlockPos, Optional<BlockState>> read, BlockPos cell) {
        Optional<BlockState> state = read.apply(cell);
        if (state.isEmpty()) {
            return false;
        }
        BlockState actual = state.get();
        return actual.isAir() || actual.is(Blocks.FIRE) || actual.is(Blocks.NETHER_PORTAL);
    }

    private static boolean isObsidian(Function<BlockPos, Optional<BlockState>> read, BlockPos cell) {
        Optional<BlockState> state = read.apply(cell);
        return state.isPresent() && state.get().is(Blocks.OBSIDIAN);
    }

    /**
     * 从一格反推出完整的门框：种子是门内侧的空格（或已经是门方块的格），
     * 先顺着空格落到左下角，再量宽高；尺寸出格或框不齐就认不出来，返回空。
     * 没加载的格没有证据，认框不能建立在读不到的世界事实上。
     */
    public static Optional<NetherPortalFrame> observe(Function<BlockPos, Optional<BlockState>> read,
            BlockPos seed, Direction.Axis axis) {
        Optional<NetherPortalFrame> sketched = sketch(read, seed, axis);
        return sketched.filter(frame -> frame.ready(read));
    }

    /**
     * 从一格黑曜石认出它围着的门框，不要求框已经齐整：残缺的框也要认得出来，
     * 缺哪几格黑曜石由 {@link #missing} 给。竖着的块往两侧找内侧，底行与顶行的块往上找，
     * 两个横轴都试；形状出格、内侧被堵得量不出时认不出，返回空。
     */
    public static Optional<NetherPortalFrame> sketchAround(Function<BlockPos, Optional<BlockState>> read,
            BlockPos obsidian) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Optional<NetherPortalFrame> frame = sketch(read, obsidian.relative(side), side.getAxis());
            if (frame.isPresent()) {
                return frame;
            }
        }
        // 底行与顶行的黑曜石，内侧在它的上（或下）方：横轴两个方向各试一遍。
        for (BlockPos seed : new BlockPos[]{obsidian.above(), obsidian.below()}) {
            for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
                Optional<NetherPortalFrame> frame = sketch(read, seed, axis);
                if (frame.isPresent()) {
                    return frame;
                }
            }
        }
        return Optional.empty();
    }

    /** 门框几何的反推：从内侧一格落到左下角，量宽高，尺寸合原版就认。 */
    private static Optional<NetherPortalFrame> sketch(Function<BlockPos, Optional<BlockState>> read,
            BlockPos seed, Direction.Axis axis) {
        if (axis == Direction.Axis.Y || axis == null || !empty(read, seed)) {
            return Optional.empty();
        }
        BlockPos bottom = seed;
        for (int i = 0; i <= MAX_SIZE && empty(read, bottom.below()); i++) {
            bottom = bottom.below();
        }
        Direction across = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        for (int i = 0; i <= MAX_SIZE && empty(read, bottom.relative(across.getOpposite())); i++) {
            bottom = bottom.relative(across.getOpposite());
        }
        int width = 0;
        while (width <= MAX_SIZE && empty(read, bottom.relative(across, width))) {
            width++;
        }
        int height = 0;
        while (height <= MAX_SIZE && empty(read, bottom.above(height))) {
            height++;
        }
        if (width < MIN_WIDTH || width > MAX_SIZE || height < MIN_HEIGHT || height > MAX_SIZE) {
            return Optional.empty();
        }
        return Optional.of(new NetherPortalFrame(bottom, axis, width, height));
    }
}
