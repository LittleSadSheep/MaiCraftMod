package org.maiwithu.maicraft.core.scan;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.scan.BlockScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 把较大的“找附近这些方块”请求分散到多刻完成，避免一次扫描卡住画面。
 * 扫描围绕发起时的位置进行，最多保留 8192 个较近结果；它返回的是观察，不是到目标的可行路线。
 */
public final class BlockSearch {

    /** 最长扫描时间；超时后返回部分结果（约 30 秒）。 */
    private static final int DEADLINE_TICKS = 600;
    /** 与同步扫描器使用相同的结果数量上限，限制内存占用和排序成本。 */
    private static final int MAX_COLLECT = 8_192;

    private static final List<BlockSearch> JOBS = new ArrayList<>();
    private static int nextId = 1;

    private final int id = nextId++;
    /** 本次查询的简短目标描述，便于记录在一行日志中，例如 {@code iron_ore} 或 {@code iron_ore+1}。 */
    private final String label;
    private long startTick = -1;
    private final UUID entityUuid;
    private final ResourceKey<Level> dimension;
    private final BlockPos center;
    private final int radius;
    private final double radiusSq;
    private final Predicate<BlockState> filter;
    private final Consumer<ScanResult> onDone;

    private final int centerChunkX, centerChunkZ, maxRing;
    /** 按访问顺序排列的区块层 Y 值，先扫描最近层（{@link SearchGeometry#sectionOrder}）。 */
    private final int[] sectionOrder;
    private int ring, perimIdx;
    private long deadline = -1;
    private int columnsScanned, columnsUnloaded;
    private int sectionsScanned;
    private final int columnsTotal;
    private boolean stoppedEarly;
    private boolean matchesTrimmed;

    /** 第 {@code want} 个最近目标的距离；停止规则只使用此值。 */
    private final SearchGeometry.NearestBound bound;
    /** {@link #matches} 中的处理水位线；此位置之前的结果已纳入 {@link #bound}。 */
    private int fed;

    // 当前仍在扫描的柱列（预算用尽时可能尚未扫完）；为空时获取下一列。
    private ChunkAccess currentChunk;
    private int currentChunkX, currentChunkZ, sectionCursor;

    private final List<BlockScanner.Hit> matches = new ArrayList<>();

    /**
     * 单次扫描的结果及覆盖账本：实际读取了 {@code columnsTotal} 中多少个区块柱列，多少个因未加载而跳过，以及扫描是否因期限而提前结束。
     * 调用方据此撰写答复；仅有命中列表无法让模型区分“没有发现”和“该区域确实没有目标”。
     */
    public record ScanResult(List<BlockScanner.Hit> matches, int columnsScanned,
                             int columnsUnloaded, int columnsTotal,
                             int sectionsScanned, boolean deadlineHit, boolean stoppedEarly,
                             boolean collectCapHit) {

        /** 是否已经实际覆盖完整的请求球形范围？ */
        public boolean coveredEverything() {
            return !deadlineHit && !stoppedEarly && !collectCapHit && columnsUnloaded == 0;
        }
    }

    private BlockSearch(UUID entityUuid, ClientLevel level, BlockPos center, int radius, int want,
                          Set<Block> targets, Consumer<ScanResult> onDone) {
        this.entityUuid = entityUuid;
        this.dimension = level.dimension();
        this.center = center.immutable();
        this.radius = radius;
        this.radiusSq = (double) radius * radius;
        this.filter = state -> targets.contains(state.getBlock());
        this.onDone = onDone;
        this.label = describe(targets);
        this.centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        this.centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        this.maxRing = Math.max(
                Math.max(SectionPos.blockToSectionCoord(center.getX() + radius) - centerChunkX,
                        centerChunkX - SectionPos.blockToSectionCoord(center.getX() - radius)),
                Math.max(SectionPos.blockToSectionCoord(center.getZ() + radius) - centerChunkZ,
                        centerChunkZ - SectionPos.blockToSectionCoord(center.getZ() - radius)));
        this.sectionOrder = SearchGeometry.sectionOrder(
                SectionPos.blockToSectionCoord(Math.max(center.getY() - radius, level.getMinBuildHeight())),
                SectionPos.blockToSectionCoord(Math.min(center.getY() + radius, level.getMaxBuildHeight())),
                SectionPos.blockToSectionCoord(center.getY()));
        this.bound = new SearchGeometry.NearestBound(want);
        int side = 2 * maxRing + 1;
        this.columnsTotal = side * side;
    }

    /**
     * 登记一次搜索；结果会在后续 tick 通过回调返回。
     *
     * @param want 调用方实际需要的最近命中数量，也是停止规则的配额。按实际用途设置；配额越大，扫描距离越远。
     * @return 供 {@link #cancel(int)} 使用的搜索句柄；每次搜索单独编号，而非每个同伴共用。一个同伴可能同时执行 {@code scan_blocks} 和 {@code goto} 查询，取消其中一个不能影响另一个。
     */
    // 登记一个分刻扫描任务并返回编号，暂不扫描；完成时才调用 onDone。
    public static int start(UUID entityUuid, ClientLevel level, BlockPos center, int radius, int want,
                            Set<Block> targets, Consumer<ScanResult> onDone) {
        BlockSearch job = new BlockSearch(entityUuid, level, center, radius, want, targets, onDone);
        JOBS.add(job);
        return job.id;
    }

    /** 放弃指定搜索且不再触发回调；未知或已完成的编号不产生任何操作。 */
    // 只从扫描列表移除，不调用完成回调；取消后的调用结算由外层负责。
    public static void cancel(int id) {
        JOBS.removeIf(job -> job.id == id);
    }

    /** 本地角色或世界退出时，放弃所有等待中的扫描。 */
    public static void cancelAll() {
        JOBS.clear();
    }

    /** 在共享客户端 tick 预算内推进所有等待中的扫描。 */
    // 按列表顺序给任务机会，共享本刻扫描预算；前面的任务可能先用完额度。
    public static void tick(ClientLevel level) {
        if (JOBS.isEmpty()) return;
        SearchBudget.refresh(level.getGameTime());
        for (BlockSearch job : List.copyOf(JOBS)) {
            if (JOBS.contains(job) && job.tickOne(level)) JOBS.remove(job);
        }
    }

    /** @return 已完成并发送答复时返回 true。 */
    // 记住扫到哪一圈、哪列和哪个高度段；用完额度就暂停在这里，下刻接着扫。
    // 三十秒左右的游戏刻期限从首次推进时开始，不按每个任务实际获得的 CPU 时间计。
    private boolean tickOne(ClientLevel level) {
        long now = level.getGameTime();
        if (!level.dimension().equals(dimension)) {
            finish(now, true);
            return true;
        }
        if (deadline < 0) {
            startTick = now;
            deadline = startTick + DEADLINE_TICKS;
        }
        if (now >= deadline) {
            finish(now, true);
            return true;
        }
        while (true) {
            if (currentChunk == null && !nextColumn(level)) {
                if (!stoppedEarly && ring <= maxRing) return false; // 本刻检查预算耗尽且搜索范围尚未走完，保留任务供下一刻续扫。
                finish(level.getGameTime(), false);   // 螺旋范围已走完，或已证明下一环不会出现更近目标，结算本次扫描。
                return true;
            }
            // 每次按预算扫描当前柱列的一层区块，并从最近层开始。
            while (sectionCursor < sectionOrder.length) {
                if (!SearchBudget.trySectionScan()) return false;
                BlockScanner.scanChunkSection(level, currentChunk,
                        currentChunkX, sectionOrder[sectionCursor], currentChunkZ,
                        center, radius, radiusSq, filter, matches);
                sectionCursor++;
                sectionsScanned++;
                feedBound();
                if (matches.size() > MAX_COLLECT) {
                    // 内存上限不能让扫描停在同一环的中间：相邻区块可能比先扫描的高密度区块包含更近的目标。
                    matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
                    matches.subList(MAX_COLLECT, matches.size()).clear();
                    matchesTrimmed = true;
                    fed = matches.size();
                }
            }
            currentChunk = null;
            columnsScanned++;
        }
    }

    /**
     * 将螺旋扫描中的下一柱列解析到 {@link #currentChunk}，统计并跳过区块未加载的柱列。
     * 只有螺旋范围耗尽或共享柱列检查预算用尽时才返回 false；未加载柱列同样消耗配额，避免大范围空扫描绕过 tick 期限。
     */
    // 按方形圈取下一列；未加载区块只计为缺失，不主动加载。已有最近结果足够好时可以证明后续外圈无需再扫。
    private boolean nextColumn(ClientLevel level) {
        while (ring <= maxRing) {
            if (perimIdx >= RingSpiral.perimeter(ring)) {
                if (SearchGeometry.canStop(ring, bound)) {
                    // 已找到的最近 `want` 个目标都比下一环可能出现的任何目标更近。
                    stoppedEarly = true;
                    return false;
                }
                ring++;
                perimIdx = 0;
                continue;
            }
            if (!SearchBudget.tryCheck()) return false;
            int[] d = RingSpiral.offset(ring, perimIdx++);
            int cx = centerChunkX + d[0];
            int cz = centerChunkZ + d[1];
            ChunkAccess chunk = BlockScanner.loadedChunk(level, cx, cz);
            if (chunk == null) {
                columnsUnloaded++;
                continue;
            }
            currentChunk = chunk;
            currentChunkX = cx;
            currentChunkZ = cz;
            sectionCursor = 0;
            return true;
        }
        return false;
    }

    /** 将上次调用后发现的命中交给距离边界，供停止规则读取。 */
    private void feedBound() {
        for (int i = fed; i < matches.size(); i++) {
            bound.offer(matches.get(i).distance());
        }
        fed = matches.size();
    }

    // 先移除任务再调用回调，返回扫描覆盖、未加载、提前停止和截断信息；找到一些结果不等于范围全部查完。
    private void finish(long now, boolean deadlineHit) {
        // 先移除当前搜索再调用外部回调，因为回调可能新增、取消搜索或抛出异常。
        JOBS.remove(this);
        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        // 每次搜索只记一行，但必须包含排障所需信息：查询内容、返回结果、停止原因和扫描成本。
        // 即使没有调试构建，也可通过停止原因和未加载数量解释角色为何找不到目标。
        Constants.LOG.info("[maicraft-scan] {} r={} → {} hit(s){} | {} | {}/{} columns read,"
                        + " {} not loaded | {} tick(s)",
                label, radius, matches.size(),
                matches.isEmpty() ? "" : String.format(", nearest %.1f", matches.get(0).distance()),
                stopReason(deadlineHit), columnsScanned, columnsTotal, columnsUnloaded,
                startTick < 0 ? 0 : now - startTick);
        onDone.accept(new ScanResult(List.copyOf(matches),
                columnsScanned + (currentChunk == null ? 0 : 1), columnsUnloaded, columnsTotal,
                sectionsScanned, deadlineHit, stoppedEarly, matchesTrimmed));
    }

    private String stopReason(boolean deadlineHit) {
        if (deadlineHit) return "deadline";
        if (stoppedEarly) return "proved nearest at ring " + ring;
        if (matchesTrimmed) return "covered the whole radius; retained nearest matches";
        return "covered the whole radius";
    }

    /** 单个目标记为 {@code iron_ore}，目标集合记为 {@code iron_ore+1}；只占一行日志，不展开成列表。 */
    private static String describe(Set<Block> targets) {
        Iterator<Block> it = targets.iterator();
        if (!it.hasNext()) return "nothing";
        String first = BuiltInRegistries.BLOCK.getKey(it.next()).getPath();
        return targets.size() > 1 ? first + "+" + (targets.size() - 1) : first;
    }
}
