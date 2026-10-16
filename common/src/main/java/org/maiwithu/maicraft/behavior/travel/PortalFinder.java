// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * 找活门：已加载区里找要去的维度的那种门方块，找出还能走进去的那一列。
 *
 * <p>扫描交给方块扫描服务分刻推进，一刻只花小预算；门方块还在才算活门，
 * 扫描没扫完不能当成"附近没有"。命中先复核实际方块，再认整扇门（六向连通，
 * 至多二百五十六格，防止把两扇挨着的门当一扇），然后按每列底部挑入口——
 * 高台上的中层门格没有落脚面，只有脚下不是门格的那一格才是能走进去的位置。
 * 已经试过进不去的入口不再挑，同一扇门的其他入口仍在候选里；窗口内的候选全部
 * 试败才扩大扫描窗口，不把"最近的几个都试过"当成"已加载区都试过"。
 */
public final class PortalFinder {

    /** 扫描起点要的候选数：够挑最近的，又不让扫描为凑数跑太远。 */
    private static final int INITIAL_WINDOW = 32;
    /** 一次查询给索引的建账预算（毫秒级粒度由扫描服务掌握）。 */
    private static final int QUERY_BUDGET = 200;
    /** 整扇门识别的格数上限：下界门框最大 23×23，留足余量又封得住失控洪泛。末地出口门认整扇门也用它。 */
    static final int MAX_CONNECTED_CELLS = 256;

    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    /** 候选窗口：窗口内全部试败才加倍，不把固定次数的失败当成整个已加载区都试过。 */
    private int window = INITIAL_WINDOW;
    /** 登记过门方块的世界与方块种类：换门种类或换世界时重新登记。 */
    private ClientLevel registeredLevel;
    private Block registeredBlock;

    public PortalFinder(Supplier<PlayerContext> context, BlockScanService scans) {
        this.context = context;
        this.scans = scans;
    }

    /**
     * 问一轮：现在找得到哪些还能试的入口。扫描没扫完时已找到的照常给出，
     * 一格都没有说明不了"没有"，调用方下一刻再来问。
     *
     * @param portalBlock    要找的门方块（按维度路由给）
     * @param maxDistance    愿意为"附近没有"承担的搜索范围（格）
     * @param attemptedCells 已经试过进不去的入口格；它们不再挑出来
     */
    public Round scan(Block portalBlock, int maxDistance, Set<Long> attemptedCells) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return new Round(List.of(), true);
        }
        ClientLevel level = current.level();
        BlockPos center = current.localPlayer().blockPosition();
        ensureRegistered(level, portalBlock);
        int chunkRadius = Math.max(1, (maxDistance + 15) / 16);
        var found = scans.query(level, center, Set.of(portalBlock), window, chunkRadius,
                QUERY_BUDGET, Set.of(), false);
        List<Entrance> entrances = new ArrayList<>();
        for (BlockPos hit : found.hits()) {
            // 命中先复核实际方块：缓存里的旧位置可能已经被拆了；超出声明范围的也不算。
            if (!alive(level, hit, portalBlock)
                    || Math.sqrt(hit.distSqr(center)) > maxDistance
                    || attemptedCells.contains(hit.asLong())) {
                continue;
            }
            Set<Long> whole = connectedCells(cell -> readState(level, cell), hit, MAX_CONNECTED_CELLS);
            for (BlockPos column : entranceColumns(whole, attemptedCells, center)) {
                entrances.add(new Entrance(column, whole));
            }
        }
        // 窗口内的候选全部试败（一个入口都挑不出来）才扩大窗口，把更远的门也纳入候选。
        if (entrances.isEmpty() && found.hits().size() >= window && window < Integer.MAX_VALUE / 4) {
            window *= 2;
        }
        return new Round(List.copyOf(entrances), found.complete());
    }

    /** 试过一轮后把窗口收回初始值：下一次行程重新从最近的门找起。 */
    public void resetWindow() {
        window = INITIAL_WINDOW;
    }

    private void ensureRegistered(ClientLevel level, Block portalBlock) {
        if (registeredLevel == level && registeredBlock == portalBlock) {
            return;
        }
        if (registeredLevel != null && registeredBlock != null) {
            scans.unregister(registeredLevel, Set.of(registeredBlock));
        }
        scans.register(level, Set.of(portalBlock));
        registeredLevel = level;
        registeredBlock = portalBlock;
    }

    private static Optional<BlockState> readState(ClientLevel level, BlockPos cell) {
        // 没加载的格没有证据：不把"读不到"当成"不是门"。
        return level.isLoaded(cell) ? Optional.of(level.getBlockState(cell)) : Optional.empty();
    }

    /** 这一格现在还是活的门方块：区块加载着、方块对得上。 */
    public static boolean alive(ClientLevel level, BlockPos cell, Block portalBlock) {
        return level.isLoaded(cell) && level.getBlockState(cell).is(portalBlock);
    }

    /** {@link #alive} 的读端形式：给"按格读方块"的函数回答活没活，离线可测。 */
    public static boolean aliveOf(Function<BlockPos, Optional<BlockState>> read, BlockPos cell, Block portalBlock) {
        Optional<BlockState> state = read.apply(cell);
        return state.isPresent() && state.get().is(portalBlock);
    }

    /**
     * 整扇门的全部门格：从种子格六向洪泛，同种方块连成一片，至多 {@code maxCells} 格。
     * 读端给不出状态的格（没加载）当墙处理，洪泛不穿过去；读不到的世界事实不冒充门格。
     */
    public static Set<Long> connectedCells(Function<BlockPos, Optional<BlockState>> read, BlockPos seed,
            int maxCells) {
        Optional<BlockState> seedState = read.apply(seed);
        if (seedState.isEmpty()) {
            return Set.of();
        }
        Block block = seedState.get().getBlock();
        Set<Long> cells = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(seed.immutable());
        while (!open.isEmpty() && cells.size() < maxCells) {
            BlockPos cell = open.removeFirst();
            if (cells.contains(cell.asLong())) {
                continue;
            }
            Optional<BlockState> state = read.apply(cell);
            if (state.isEmpty() || !state.get().is(block) || !cells.add(cell.asLong())) {
                continue;
            }
            open.add(cell.above());
            open.add(cell.below());
            open.add(cell.north());
            open.add(cell.south());
            open.add(cell.east());
            open.add(cell.west());
        }
        return Set.copyOf(cells);
    }

    /**
     * 按每列底部挑入口：脚下不是门格的那一格才走得进去（高台上的中层门格没有落脚面），
     * 试过进不去的不再挑，按离人近到远排好序。
     */
    public static List<BlockPos> entranceColumns(Set<Long> cells, Set<Long> attempted, BlockPos from) {
        List<BlockPos> entrances = new ArrayList<>();
        for (Long key : cells) {
            BlockPos cell = BlockPos.of(key);
            if (!cells.contains(cell.below().asLong()) && !attempted.contains(key)) {
                entrances.add(cell);
            }
        }
        entrances.sort(Comparator.comparingDouble(cell -> cell.distSqr(from)));
        return entrances;
    }

    /** 一轮扫描的收成。 */
    public record Round(List<Entrance> entrances, boolean complete) {

        /** 有入口可试。 */
        public boolean hasEntrance() {
            return !entrances.isEmpty();
        }
    }

    /**
     * 一个入口： {@code cell} 是要走进去的那一格（整扇门按列底部的那格），
     * {@code portalCells} 是整扇门的门格（走近时保护它、进门时认它）。
     */
    public record Entrance(BlockPos cell, Set<Long> portalCells) {
        public Entrance {
            portalCells = Set.copyOf(portalCells);
        }
    }
}
