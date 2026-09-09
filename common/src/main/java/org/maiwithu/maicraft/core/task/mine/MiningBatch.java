package org.maiwithu.maicraft.core.task.mine;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;

/**
 * 把已经找到的相邻矿石或同一竖直树干分成一小批，决定先继续挖还是先捡地上材料。
 * 它不负责寻路和确认拾取，也不会凭空把远处的新方块加进任务。
 */
record MiningBatch(Set<BlockPos> targets, boolean followTrunk) {
    // 只从已经知道的同种材料里找相邻格，不额外扫描世界；原木批次只沿上下方向连接。
    static MiningBatch connected(BlockPos seed, Set<BlockPos> sameMaterial,
                                 boolean uprightLogs, boolean naturalLeaves) {
        Set<BlockPos> selected = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        if (sameMaterial.contains(seed)) { selected.add(seed.immutable()); pending.add(seed); }
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            for (Direction direction : Direction.values()) {
                // A natural trunk can be followed vertically, but a touching wall/roof or a
                // neighbouring tree must not become extra work merely to postpone pickup.
                if (uprightLogs && direction.getAxis() != Direction.Axis.Y) continue;
                BlockPos next = current.relative(direction);
                if (sameMaterial.contains(next) && selected.add(next)) pending.addLast(next);
            }
        }
        return new MiningBatch(Set.copyOf(selected), uprightLogs && naturalLeaves);
    }

    // 背包已经够、加上地上物品就够、掉落物等了一分钟或有风险时，都应先捡。地上数量本身不算已获得。
    static boolean shouldCollect(int carried, int requested, int ownedLoose,
                                 long waitingTicks, boolean riskyDrop) {
        // Loose output is only a reason to stop producing more. It never increases the
        // reported gathered count, which remains the synchronized inventory delta.
        return carried >= requested || (long) carried + ownedLoose >= requested
                || waitingTicks >= 20L * 60L || riskyDrop;
    }

    // 用树冠外观作判断：树顶附近至少两侧连着距原木一格的非持久树叶，再在小范围内数到八片叶子。
    // 这是识别规则，不是读取游戏的“自然生成”来源标签。
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
                var state = world.getBlockState(leaf);
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
            if (Math.abs(leaf.getX() - top.getX()) > 2 || Math.abs(leaf.getZ() - top.getZ()) > 2
                    || leaf.getY() < top.getY() - 1 || leaf.getY() > top.getY() + 2
                    || !visited.add(leaf) || !loaded.test(leaf)) continue;
            if (!naturalLeaf(world.getBlockState(leaf))) continue;
            if (++leaves >= 8) return true;
            for (Direction side : Direction.values()) pending.addLast(leaf.relative(side));
        }
        return false;
    }

    private static boolean naturalLeaf(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT);
    }

    // 往下最多检查四格；遇到危险或未加载就认为不能放心等它落地，找到有碰撞的支撑才算可落。
    static boolean safeDropLanding(BlockPos position, BlockGetter world, Predicate<BlockPos> loaded) {
        for (int depth = 0; depth < 4; depth++) {
            BlockPos below = position.below(depth);
            if (!loaded.test(below)) return false;
            var state = world.getBlockState(below);
            if (MovementHelper.avoidWalkingInto(state)) return false;
            if (!state.getCollisionShape(world, below).isEmpty()) return true;
        }
        return false;
    }
}
