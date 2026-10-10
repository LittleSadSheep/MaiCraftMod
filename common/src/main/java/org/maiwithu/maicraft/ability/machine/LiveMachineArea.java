// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 读一片范围格子的生产读端：以给定的格为中心读一个球形范围里的非空方块，
 * 一刻只花一小笔预算，没读完的下一刻接着读。同一轮扫描（维度、中心、半径都没变）接着上次的位置，
 * 范围变了从头再来；读的期间换了世界，如实报告这一轮作废。
 *
 * <p>未加载的格逐格数出来，不按空气算——机器查看的结论里要写"有几格不知道"。
 * 只读客户端世界，不触发区块加载，也不判断看不看得见：机器分组要数全一台机器的格，
 * 机器内部的格本来就不是靠视线认的。
 */
final class LiveMachineArea implements ReadsMachineArea {

    /** 一刻最多读多少格已加载的方块：与扫描服务同一量级的预算，保证不阻塞渲染线程。 */
    private static final int CELLS_PER_TICK = 16_384;
    /** 一刻读取的时间上限：格子数是常规约束，这一条挡住方块实体查询偶尔变慢的情况。 */
    private static final long NANOS_PER_TICK = 3_000_000L;

    private final Supplier<PlayerContext> context;
    /** 当前这轮扫描的维度、中心与半径；和上一次调用一样才接着读，变了重新开始。 */
    private String scanningDimension;
    private BlockPos scanningCenter;
    private int scanningRadius = -1;
    /** 平铺游标：box 里扫到第几格；box 按先 y 再 z 再 x 的顺序展开。 */
    private long cursor;
    private long totalCells;
    /** 这轮扫描的世界高度范围：出世界边界的格不数不读。 */
    private int scannedMinY;
    private int scannedMaxY;
    private int unloadedSoFar;

    LiveMachineArea(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Round scan(String dimension, BlockPos center, int radius) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        // 不在世界里：这轮没有结论；还有一轮没读完的扫描就当作换了世界，如实作废。
        if (level == null) {
            boolean hadScan = scanningDimension != null;
            reset();
            return Round.empty(hadScan);
        }
        String here = level.dimension().location().toString();
        if (scanningDimension != null && !scanningDimension.equals(here)) {
            // 上一轮是另一个世界的：作废，这轮从头开始。
            reset();
            return Round.empty(true);
        }
        if (!sameScan(dimension, center, radius)) {
            scanningDimension = dimension;
            scanningCenter = center.immutable();
            scanningRadius = radius;
            cursor = 0;
            unloadedSoFar = 0;
            scannedMinY = Math.max(center.getY() - radius, level.getMinBuildHeight());
            scannedMaxY = Math.min(center.getY() + radius, level.getMaxBuildHeight() - 1);
            totalCells = (2L * radius + 1) * (2L * radius + 1)
                    * Math.max(1, scannedMaxY - scannedMinY + 1);
        }
        return advance(level, center, radius);
    }

    // 接着读一小段：预算用完或读完了才返回；每轮给出的只是这一刻新读出的格子。
    private Round advance(ClientLevel level, BlockPos center, int radius) {
        long started = System.nanoTime();
        int examined = 0;
        List<Cell> cells = new ArrayList<>();
        int xSize = 2 * radius + 1;
        int zSize = 2 * radius + 1;
        long radiusSquared = (long) radius * radius;
        while (cursor < totalCells && examined < CELLS_PER_TICK
                && (examined == 0 || System.nanoTime() - started < NANOS_PER_TICK)) {
            long index = cursor++;
            // 平铺下标换回三维：y 最外层（从低到高，先砍掉世界外的高度），z 其次，x 最内层。
            int x = center.getX() - radius + (int) (index % xSize);
            int z = center.getZ() - radius + (int) (index / xSize % zSize);
            int y = scannedMinY + (int) (index / (xSize * zSize));
            long dx = x - center.getX();
            long dy = y - center.getY();
            long dz = z - center.getZ();
            // 球形范围外的格不算工作也不读世界。
            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                continue;
            }
            BlockPos at = new BlockPos(x, y, z);
            if (!level.hasChunkAt(at)) {
                // 没加载的格逐格记账：结论里要写有几格不知道，不能按空气算。
                unloadedSoFar++;
                continue;
            }
            examined++;
            BlockState state = level.getBlockState(at);
            if (state.isAir()) {
                continue;
            }
            cells.add(new Cell(at, state, level.getBlockEntity(at) != null));
        }
        boolean complete = cursor >= totalCells;
        if (complete) {
            reset();
        }
        return new Round(cells, unloadedSoFar, complete, false);
    }

    private boolean sameScan(String dimension, BlockPos center, int radius) {
        return scanningDimension != null && scanningDimension.equals(dimension)
                && scanningRadius == radius && scanningCenter != null && scanningCenter.equals(center);
    }

    private void reset() {
        scanningDimension = null;
        scanningCenter = null;
        scanningRadius = -1;
        cursor = 0;
        totalCells = 0;
        scannedMinY = 0;
        scannedMaxY = 0;
        unloadedSoFar = 0;
    }
}
