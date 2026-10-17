// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 要塞里的一扇末地传送门：十二个末地门框架方块围着一圈三乘三的内侧，
 * 每块框架朝内、上面插着末影之眼；十二块都插满眼门面出现，走进去落到末地。
 *
 * <p>框架方块只在世界生成里出现，角色造不出来——这里的几何只做辨认与插眼，
 * 不编施工蓝图。激活与否以内侧长满末地传送门方块为准，不靠插了几只眼猜。
 */
public record EndPortalFrame(BlockPos center) {

    public EndPortalFrame {
        center = center.immutable();
    }

    /** 十二块框架：距中心两格的四条边，每块朝内（面向中心）。 */
    public Map<BlockPos, Direction> frames() {
        Map<BlockPos, Direction> frames = new LinkedHashMap<>();
        for (Direction outward : Direction.Plane.HORIZONTAL) {
            for (int offset = -1; offset <= 1; offset++) {
                frames.put(center.relative(outward, 2).relative(outward.getClockWise(), offset),
                        outward.getOpposite());
            }
        }
        return frames;
    }

    /** 内侧九格：门面出现的地方，也是进门走进去的地方。 */
    public List<BlockPos> interior() {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.add(center.offset(x, 0, z));
            }
        }
        return List.copyOf(cells);
    }

    /** 十二块框架都在、朝向都对：这扇门是完整的，缺的只是眼。 */
    public boolean intact(Function<BlockPos, Optional<BlockState>> read) {
        return frames().entrySet().stream().allMatch(entry -> {
            Optional<BlockState> state = read.apply(entry.getKey());
            return state.isPresent() && state.get().is(Blocks.END_PORTAL_FRAME)
                    && state.get().getValue(EndPortalFrameBlock.FACING) == entry.getValue();
        });
    }

    /** 还空着的框架格：插末影之眼就插这些，按离人近到远插。 */
    public List<BlockPos> missingEyes(Function<BlockPos, Optional<BlockState>> read) {
        return frames().keySet().stream()
                .filter(cell -> {
                    Optional<BlockState> state = read.apply(cell);
                    return state.isPresent() && !state.get().getValue(EndPortalFrameBlock.HAS_EYE);
                })
                .toList();
    }

    /** 内侧已经长满末地传送门方块：门开了，进去就到末地。 */
    public boolean active(Function<BlockPos, Optional<BlockState>> read) {
        return interior().stream().allMatch(cell -> {
            Optional<BlockState> state = read.apply(cell);
            return state.isPresent() && state.get().is(Blocks.END_PORTAL);
        });
    }

    /**
     * 从一块框架认出它围着的那扇门：框架块朝内，沿它的朝向往里两格、
     * 左右各挪一格试三个可能的中心，哪个中心凑得齐完整一圈就算它。
     */
    public static Optional<EndPortalFrame> observe(Function<BlockPos, Optional<BlockState>> read, BlockPos frameCell) {
        Optional<BlockState> state = read.apply(frameCell);
        if (state.isEmpty() || !state.get().is(Blocks.END_PORTAL_FRAME)) {
            return Optional.empty();
        }
        Direction inward = state.get().getValue(EndPortalFrameBlock.FACING);
        for (int offset = -1; offset <= 1; offset++) {
            EndPortalFrame ring = new EndPortalFrame(
                    frameCell.relative(inward, 2).relative(inward.getClockWise(), offset));
            if (ring.intact(read)) {
                return Optional.of(ring);
            }
        }
        return Optional.empty();
    }
}
