// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 临时方块的找链与收回顺序：某格够不着或没有可点击面时，从目标六向的空气格向外找到最近的已有方块，
 * 垫一条可拆的链接到目标旁边；收回时先收站着够得着的，再沿自己的柱逐格下撤，每格拆前确认下一格落得稳。
 * 纯函数，只读世界；放与拆由施工任务做，账记在世界记忆里。
 */
public final class TemporaryBlocks {

    /** 先往下找，再四周，最后往上：人垫脚先垫脚下。 */
    private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP};
    /** 一次搜索最多看多少格；再多说明周围空得离谱，不是垫几块能解决的。 */
    private static final int MAX_CELLS = 4096;
    /** 一条链最多几块。 */
    public static final int MAX_CHAIN = 32;
    private static final int REACH_SIDEWAYS = 4;
    private static final int REACH_DOWN = 24;
    private static final int REACH_UP = 2;

    private TemporaryBlocks() {}

    /**
     * 从目标旁边找一条垫块链：按离目标的步数一圈圈往外，先下后四周最后上；碰到第一块已有方块就沿来路还原。
     * 顺序从贴着真实地形的那一端开始，逐块接到目标旁边。链末端（贴目标的那块）必须能给目标提供要的点击面。
     * 找不到或超过 {@link #MAX_CHAIN} 块返回空列表。
     *
     * @param mayPlace      这一格允不允许放临时方块（许可与保护说了算）
     * @param usableSupport 贴着目标的那块能不能当目标的点击面（朝向有要求的方块要挑面）
     */
    public static List<BlockPos> chain(ReadsBlocks world, BlockPos target, Predicate<BlockPos> mayPlace, Predicate<BlockPos> usableSupport) {
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        ArrayDeque<BlockPos> search = new ArrayDeque<>();
        for (Direction direction : DIRECTIONS) {
            BlockPos start = target.relative(direction);
            if (available(world, target, start, mayPlace) && usableSupport.test(start)) {
                parents.put(start, null);
                search.add(start);
            }
        }
        while (!search.isEmpty() && parents.size() <= MAX_CELLS) {
            BlockPos next = search.removeFirst();
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = next.relative(direction);
                if (neighbor.equals(target) || !world.loaded(neighbor)) continue;
                if (solid(world, neighbor)) return fromGround(next, parents);
                if (!parents.containsKey(neighbor) && available(world, target, neighbor, mayPlace)) {
                    parents.put(neighbor, next);
                    search.addLast(neighbor);
                }
            }
        }
        return List.of();
    }

    /** 已有的、站得住或点得了的方块：不是空气、不可替换、有形状。 */
    static boolean solid(ReadsBlocks world, BlockPos pos) {
        BlockState state = world.state(pos);
        return !state.isAir() && !state.canBeReplaced() && !state.getFluidState().isSource() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }

    // 只在水平四格、向下二十四格、向上两格内找已加载、允许放、现在是空气的格。
    private static boolean available(ReadsBlocks world, BlockPos target, BlockPos candidate, Predicate<BlockPos> mayPlace) {
        return !candidate.equals(target)
                && Math.abs(candidate.getX() - target.getX()) <= REACH_SIDEWAYS
                && Math.abs(candidate.getZ() - target.getZ()) <= REACH_SIDEWAYS
                && candidate.getY() >= target.getY() - REACH_DOWN && candidate.getY() <= target.getY() + REACH_UP
                && world.loaded(candidate) && mayPlace.test(candidate) && world.state(candidate).isAir();
    }

    // 沿来路还原：从贴地的那块到贴目标的那块；超过上限就放弃，不截成一条够不到目标的短链。
    private static List<BlockPos> fromGround(BlockPos anchor, Map<BlockPos, BlockPos> parents) {
        List<BlockPos> ordered = new ArrayList<>();
        for (BlockPos pos = anchor; pos != null; pos = parents.get(pos)) {
            if (ordered.size() >= MAX_CHAIN) return List.of();
            ordered.add(pos);
        }
        return List.copyOf(ordered);
    }

    /**
     * 收回顺序：先收不在脚下那一柱里的（站着就够得着的，离脚远的先收）；再收脚下这一柱，从上往下。
     * 脚下这一柱指从脚下那格往下连续的自己垫的方块。
     */
    public static List<BlockPos> removalOrder(Set<BlockPos> placed, BlockPos feet) {
        List<BlockPos> column = new ArrayList<>();
        for (BlockPos below = feet.below(); placed.contains(below); below = below.below()) column.add(below);
        List<BlockPos> others = new ArrayList<>();
        for (BlockPos pos : placed) if (!column.contains(pos)) others.add(pos);
        others.sort((a, b) -> Double.compare(b.distSqr(feet), a.distSqr(feet)));
        List<BlockPos> out = new ArrayList<>(others);
        out.addAll(column);
        return List.copyOf(out);
    }

    /**
     * 拆脚下这一格之前，下一格落得稳吗：要么下面紧挨着就是实心（落一格），要么下面连着的几格里
     * 最多空三格后有实心（不摔伤）。什么都没有或太深就不拆，这块留着记进结果。
     */
    public static boolean safeToDescend(ReadsBlocks world, BlockPos standingOn) {
        for (int drop = 1; drop <= 4; drop++) {
            BlockPos below = standingOn.below(drop);
            if (!world.loaded(below)) return false;
            if (solid(world, below)) return true;
            if (!world.state(below).getFluidState().isEmpty()) return false;
        }
        return false;
    }

    /** 当前格暂时做不了时，从它后面开始轮一圈，只保留仍待做的，把当前格留到最后再试。 */
    public static <T> List<T> defer(List<T> queue, int current, Predicate<T> pending) {
        List<T> result = new ArrayList<>();
        for (int offset = 1; offset <= queue.size(); offset++) {
            T candidate = queue.get((current + offset) % queue.size());
            if (pending.test(candidate)) result.add(candidate);
        }
        return result;
    }
}
