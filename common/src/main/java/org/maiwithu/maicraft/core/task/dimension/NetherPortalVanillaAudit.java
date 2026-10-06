// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;

/**
 * 原版 PortalShape 扫描的逐步镜像：点火后传送门没有成型时，用它给出「vanilla 眼中的框体」
 * 与逐格偏差，回执据此如实报告差异而不是只留一个布尔位。
 *
 * <p>镜像口径（对照 {@link PortalShape} 同名私有方法）：
 * <ul>
 *   <li>{@code isEmpty} = 空气 | 火系方块 | 传送门方块——火落格本身不阻碍成型；</li>
 *   <li>框块判定 = 黑曜石（原版默认 frame 谓词就是黑曜石；模组自定义 frame 块不在镜像范围）；</li>
 *   <li>成型谓词 = 框体有效 且 内格现存的传送门方块数为 0（findEmptyPortalShape）。</li>
 * </ul>
 * 扫描从火落格出发，先按 X 轴、再按 Z 轴各跑一遍，返回偏差更少的那个视角，
 * 与原版「先 X 后另一轴」的双向尝试对应。
 */
public final class NetherPortalVanillaAudit {
    /** 逐格偏差：role 说明该格在 vanilla 扫描中的职责，observed 是当下实际方块。 */
    public record Offense(BlockPos pos, String role, String observed) {}

    /**
     * 审计结论。bottomLeft 是 vanilla 扫描的锚点——沿 rightDir 的最远内沿格（宽度向
     * rightDir 反方向量回），与 {@link NetherPortalFrame#origin} 的最西/最北内沿格东西相反；
     * 为 null 表示 vanilla 无法从落格反推出该锚点（如边缘扫描撞上非框块），此时
     * width/height 保持 vanilla 退化的 1×1 且 frameValid=false。
     */
    public record Result(Direction.Axis axis, BlockPos bottomLeft, int width, int height,
                         boolean frameValid, boolean wouldFormPortal, List<Offense> offenses) {
        public List<String> offenseLines() {
            return offenses.stream()
                    .map(o -> o.pos().toShortString() + " " + o.role() + "=" + o.observed())
                    .toList();
        }
    }

    private static final int MAX_SIZE = 21;

    private final Function<BlockPos, BlockState> read;
    private final List<Offense> offenses = new ArrayList<>();

    private NetherPortalVanillaAudit(Function<BlockPos, BlockState> read) {
        this.read = read;
    }

    public static Result audit(Function<BlockPos, BlockState> read, BlockPos seed) {
        Result byX = new NetherPortalVanillaAudit(read).scan(seed.immutable(), Direction.Axis.X);
        Result byZ = new NetherPortalVanillaAudit(read).scan(seed.immutable(), Direction.Axis.Z);
        // 双轴各跑一遍后择优：能成型的优先；都不能成型时，能定出锚点（width>0）的视角更可信，
        // 再按偏差数取少——错轴视角的伪偏差不得盖住真轴视角的逐格证据。
        if (byX.wouldFormPortal() != byZ.wouldFormPortal()) return byX.wouldFormPortal() ? byX : byZ;
        if (byX.bottomLeft() != null != (byZ.bottomLeft() != null)) return byX.bottomLeft() != null ? byX : byZ;
        return byX.offenses().size() <= byZ.offenses().size() ? byX : byZ;
    }

    private Result scan(BlockPos seed, Direction.Axis axis) {
        BlockPos bottomLeft = calculateBottomLeft(seed, rightDir(axis));
        if (bottomLeft == null) {
            return new Result(axis, null, 1, 1, false, false, List.copyOf(offenses));
        }
        int width = calculateWidth(bottomLeft, axis);
        if (width <= 0) {
            return new Result(axis, bottomLeft, 1, 1, false, false, List.copyOf(offenses));
        }
        int height = calculateHeight(bottomLeft, width, axis);
        boolean valid = height >= 3 && height <= MAX_SIZE;
        // 与 vanilla 同判的关键：框体有效时扫描途中的提前停点（如可缺席的角块）不构成偏差，
        // 只有真正阻断成型的格子才进 offense 名单。
        return new Result(axis, bottomLeft, width, height, valid, valid && portalBlocks == 0,
                valid ? List.of() : List.copyOf(offenses));
    }

    private int portalBlocks = 0;

    /** 镜像 calculateBottomLeft：沿空格下行到底，再向右扫到框边内沿。 */
    private BlockPos calculateBottomLeft(BlockPos pos, Direction rightDir) {
        int guard = 0;
        while (guard++ < MAX_SIZE && isEmpty(read.apply(pos.below()))) pos = pos.below();
        Direction left = rightDir.getOpposite();
        int steps = distanceUntilEdgeAboveFrame(pos, left) - 1;
        return steps < 0 ? null : pos.relative(left, steps);
    }

    /** 镜像 getDistanceUntilEdgeAboveFrame：沿行走到第一个框块；内格下沿必须全程有框块支撑。 */
    private int distanceUntilEdgeAboveFrame(BlockPos pos, Direction direction) {
        for (int i = 0; i <= MAX_SIZE; i++) {
            BlockPos cell = pos.relative(direction, i);
            BlockState state = read.apply(cell);
            if (state == null) {
                offense(cell, "scan_unloaded", "unloaded");
                break;
            }
            if (!isEmpty(state)) {
                if (isFrame(state)) return i;
                offense(cell, "edge_frame", id(state));
                break;
            }
            BlockState below = read.apply(cell.below());
            if (below == null) {
                offense(cell.below(), "bottom_support_unloaded", "unloaded");
                break;
            }
            if (!isFrame(below)) {
                offense(cell.below(), "bottom_support", id(below));
                break;
            }
        }
        return 0;
    }

    /** 镜像 calculateWidth：从内沿向 rightDir 再量一次宽度。 */
    private int calculateWidth(BlockPos bottomLeft, Direction.Axis axis) {
        Direction rightDir = rightDir(axis);
        int width = distanceUntilEdgeAboveFrame(bottomLeft, rightDir);
        return width >= 2 && width <= MAX_SIZE ? width : 0;
    }

    /** 镜像 calculateHeight + getDistanceUntilTop + hasTopFrame：两侧立柱、顶部横梁与内格逐层核对。 */
    private int calculateHeight(BlockPos bottomLeft, int width, Direction.Axis axis) {
        Direction rightDir = rightDir(axis);
        int height = 0;
        scan:
        for (int level = 0; level < MAX_SIZE; level++) {
            BlockPos left = bottomLeft.relative(Direction.UP, level).relative(rightDir, -1);
            BlockPos right = bottomLeft.relative(Direction.UP, level).relative(rightDir, width);
            for (BlockPos pillar : new BlockPos[]{left, right}) {
                BlockState state = read.apply(pillar);
                if (state == null) { offense(pillar, "side_frame_unloaded", "unloaded"); break scan; }
                if (!isFrame(state)) { offense(pillar, "side_frame", id(state)); break scan; }
            }
            for (int i = 0; i < width; i++) {
                BlockPos cell = bottomLeft.relative(Direction.UP, level).relative(rightDir, i);
                BlockState state = read.apply(cell);
                if (state == null) { offense(cell, "interior_unloaded", "unloaded"); break scan; }
                if (!isEmpty(state)) { offense(cell, "interior_blocked", id(state)); break scan; }
                if (state.is(Blocks.NETHER_PORTAL)) portalBlocks++;
            }
            height = level + 1;
        }
        if (height < 3 || height > MAX_SIZE || !hasTopFrame(bottomLeft, width, height, axis)) return 0;
        return height;
    }

    /** 镜像 hasTopFrame：顶部横梁逐格核对。 */
    private boolean hasTopFrame(BlockPos bottomLeft, int width, int height, Direction.Axis axis) {
        Direction rightDir = rightDir(axis);
        for (int i = 0; i < width; i++) {
            BlockPos top = bottomLeft.relative(Direction.UP, height).relative(rightDir, i);
            BlockState state = read.apply(top);
            if (state == null) { offense(top, "top_frame_unloaded", "unloaded"); return false; }
            if (!isFrame(state)) { offense(top, "top_frame", id(state)); return false; }
        }
        return true;
    }

    private void offense(BlockPos pos, String role, String observed) {
        offenses.add(new Offense(pos.immutable(), role, observed));
    }

    private static Direction rightDir(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
    }

    /** 镜像 PortalShape.isEmpty：火与既有传送门都算「空内格」，不阻碍成型。 */
    private static boolean isEmpty(BlockState state) {
        return state != null && (state.isAir() || state.is(Blocks.FIRE) || state.is(Blocks.NETHER_PORTAL));
    }

    private static boolean isFrame(BlockState state) {
        return state != null && state.is(Blocks.OBSIDIAN);
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
