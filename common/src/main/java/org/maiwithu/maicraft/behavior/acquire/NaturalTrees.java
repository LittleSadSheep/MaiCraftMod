// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 天然树的辨认：砍树只砍长着的树，不拆玩家用原木搭的木屋、栅栏柱、横梁。
 * 看的是树冠的样子——树干顶上附近至少两侧连着贴着原木（距离 1）的非持久树叶，再在树顶附近小范围里数到八片叶子。
 * 这是识别规则，不是读游戏的"自然生成"标签（客户端读不到）。
 */
public final class NaturalTrees {

    /** 一根树干最多往上下各找这么多格：再高的原木柱不是树。 */
    private static final int MAX_TRUNK = 40;
    /** 树顶附近数到这么多片天然树叶就算有树冠。 */
    private static final int CROWN_LEAVES = 8;

    private NaturalTrees() {}

    /** 这一格是不是原木（含菌柄）。 */
    public static boolean isLog(BlockState state) {
        if (state.is(BlockTags.LOGS)) return true;
        // 标签还没同步过来（离线测试、刚进世界）时按注册名兜底：原木与菌柄都叫 *_log / *_stem，而且是柱状方块。
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.getBlock() instanceof RotatedPillarBlock && (path.endsWith("_log") || path.endsWith("_stem"));
    }

    /**
     * 这一格原木是不是长在一棵天然树上：沿竖直方向连出整根树干（同一种原木），看树干顶上有没有树冠。
     * 同一根树干查过一次就记在 checked 里（键是树干顶），一次扫描里不重复数叶子。
     */
    public static boolean partOfNaturalTree(BlockPos log, BlockGetter world, Predicate<BlockPos> loaded,
            Map<BlockPos, Boolean> checked) {
        Set<BlockPos> trunk = trunkOf(log, world, loaded);
        if (trunk.isEmpty()) return false;
        BlockPos top = trunk.stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        return checked.computeIfAbsent(top, key -> hasNaturalCrown(trunk, world, loaded));
    }

    /** 从这一格原木沿竖直方向连出整根树干：上下都是同一种原木就连着。 */
    static Set<BlockPos> trunkOf(BlockPos log, BlockGetter world, Predicate<BlockPos> loaded) {
        Set<BlockPos> trunk = new HashSet<>();
        if (!loaded.test(log)) return trunk;
        BlockState kind = world.getBlockState(log);
        if (!isLog(kind)) return trunk;
        trunk.add(log.immutable());
        for (Direction direction : new Direction[] {Direction.UP, Direction.DOWN}) {
            BlockPos next = log.relative(direction);
            for (int i = 0; i < MAX_TRUNK && loaded.test(next) && world.getBlockState(next).is(kind.getBlock()); i++) {
                trunk.add(next.immutable());
                next = next.relative(direction);
            }
        }
        return trunk;
    }

    /** 这根树干顶上有没有天然树冠：树顶附近至少两侧贴着距离 1 的非持久树叶，再在小范围里数到八片。 */
    static boolean hasNaturalCrown(Set<BlockPos> trunk, BlockGetter world, Predicate<BlockPos> loaded) {
        if (trunk.isEmpty()) return false;
        BlockPos top = trunk.stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        Set<BlockPos> visited = new HashSet<>();
        Set<Direction> attachedSides = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        for (BlockPos log : trunk) {
            if (log.getY() < top.getY() - 1) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos leaf = log.relative(side);
                if (!loaded.test(leaf)) continue;
                BlockState state = world.getBlockState(leaf);
                if (naturalLeaf(state) && state.getValue(LeavesBlock.DISTANCE) == 1) {
                    attachedSides.add(side);
                    pending.add(leaf);
                }
            }
        }
        if (attachedSides.size() < 2) return false;
        int leaves = 0;
        while (!pending.isEmpty()) {
            BlockPos leaf = pending.removeFirst();
            // 只在树顶附近的小范围里数：水平 ±2、往下 1、往上 2；再远的是邻树或别的东西。
            if (Math.abs(leaf.getX() - top.getX()) > 2 || Math.abs(leaf.getZ() - top.getZ()) > 2
                    || leaf.getY() < top.getY() - 1 || leaf.getY() > top.getY() + 2
                    || !visited.add(leaf) || !loaded.test(leaf)) continue;
            if (!naturalLeaf(world.getBlockState(leaf))) continue;
            if (++leaves >= CROWN_LEAVES) return true;
            for (Direction side : Direction.values()) pending.addLast(leaf.relative(side));
        }
        return false;
    }

    // 天然的树叶：树叶方块且不是玩家放的（放的树叶是持久的，不会腐烂）。
    private static boolean naturalLeaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT);
    }
}
