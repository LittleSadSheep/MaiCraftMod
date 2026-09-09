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

    /** Hard stop: convert a crawling scan into a partial answer (30s). */
    private static final int DEADLINE_TICKS = 600;
    /** Same collect cap as the synchronous scanner — bounds memory and sort. */
    private static final int MAX_COLLECT = 8_192;

    private static final List<BlockSearch> JOBS = new ArrayList<>();
    private static int nextId = 1;

    private final int id = nextId++;
    /** What was asked for, short enough for one log line: {@code iron_ore} / {@code iron_ore+1}. */
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
    /** Section Y values in visit order — nearest layer first ({@link SearchGeometry#sectionOrder}). */
    private final int[] sectionOrder;
    private int ring, perimIdx;
    private long deadline = -1;
    private int columnsScanned, columnsUnloaded;
    private int sectionsScanned;
    private final int columnsTotal;
    private boolean stoppedEarly;
    private boolean matchesTrimmed;

    /** How far out the nearest {@code want} reach — the stop rule's whole input. */
    private final SearchGeometry.NearestBound bound;
    /** Watermark into {@link #matches} — everything below it is already in {@link #bound}. */
    private int fed;

    // Column in progress (budget ran dry mid-column); null = fetch next.
    private ChunkAccess currentChunk;
    private int currentChunkX, currentChunkZ, sectionCursor;

    private final List<BlockScanner.Hit> matches = new ArrayList<>();

    /**
     * One scan's answer plus its coverage ledger: how many of {@code columnsTotal}
     * chunk columns were actually read, how many were skipped for not being
     * loaded, and whether the deadline cut the walk short. The caller words the
     * reply from these — a hit list alone can't tell the model whether "nothing
     * found" means "nothing there".
     */
    public record ScanResult(List<BlockScanner.Hit> matches, int columnsScanned,
                             int columnsUnloaded, int columnsTotal,
                             int sectionsScanned, boolean deadlineHit, boolean stoppedEarly,
                             boolean collectCapHit) {

        /** Did the walk actually cover the whole requested sphere? */
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
     * Register a search; the result arrives via the callback on a later tick.
     *
     * @param want how many nearest hits the caller actually needs — the stop rule's quota.
     *             Ask for what you will use: a bigger number walks further to prove itself.
     * @return a handle for {@link #cancel(int)}, per SEARCH rather than per companion —
     *         one pet can have a {@code scan_blocks} query and a {@code goto} lookup in
     *         flight at once, and abandoning one must not silence the other.
     */
    // 登记一个分刻扫描任务并返回编号，暂不扫描；完成时才调用 onDone。
    public static int start(UUID entityUuid, ClientLevel level, BlockPos center, int radius, int want,
                            Set<Block> targets, Consumer<ScanResult> onDone) {
        BlockSearch job = new BlockSearch(entityUuid, level, center, radius, want, targets, onDone);
        JOBS.add(job);
        return job.id;
    }

    /** Abandon one search: no callback will fire. Unknown / already-finished ids are a no-op. */
    // 只从扫描列表移除，不调用完成回调；取消后的调用结算由外层负责。
    public static void cancel(int id) {
        JOBS.removeIf(job -> job.id == id);
    }

    /** Abandon every pending scan when the local body or world disappears. */
    public static void cancelAll() {
        JOBS.clear();
    }

    /** Advance all pending scans under the shared client-tick budget. */
    // 按列表顺序给任务机会，共享本刻扫描预算；前面的任务可能先用完额度。
    public static void tick(ClientLevel level) {
        if (JOBS.isEmpty()) return;
        SearchBudget.refresh(level.getGameTime());
        for (BlockSearch job : List.copyOf(JOBS)) {
            if (JOBS.contains(job) && job.tickOne(level)) JOBS.remove(job);
        }
    }

    /** @return true when finished (reply sent). */
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
                if (!stoppedEarly && ring <= maxRing) return false; // column-check budget exhausted
                finish(level.getGameTime(), false);   // spiral exhausted
                return true;
            }
            // Scan the in-progress column one budgeted section at a time, nearest layer first.
            while (sectionCursor < sectionOrder.length) {
                if (!SearchBudget.trySectionScan()) return false;
                BlockScanner.scanChunkSection(level, currentChunk,
                        currentChunkX, sectionOrder[sectionCursor], currentChunkZ,
                        center, radius, radiusSq, filter, matches);
                sectionCursor++;
                sectionsScanned++;
                feedBound();
                if (matches.size() > MAX_COLLECT) {
                    // A memory bound must not stop mid-ring: an adjacent section may contain
                    // closer cells than the dense section visited first.
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
     * Resolve the next spiral column into {@link #currentChunk}, tallying and
     * skipping columns whose chunk isn't loaded. Returns false only when the
     * spiral is exhausted or the shared column-check budget is spent. Even unloaded columns
     * consume a permit so a large empty search cannot bypass the tick deadline.
     */
    // 按方形圈取下一列；未加载区块只计为缺失，不主动加载。已有最近结果足够好时可以证明后续外圈无需再扫。
    private boolean nextColumn(ClientLevel level) {
        while (ring <= maxRing) {
            if (perimIdx >= RingSpiral.perimeter(ring)) {
                if (SearchGeometry.canStop(ring, bound)) {
                    // The nearest `want` are already closer than anything the next ring could hold.
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

    /** Hand the hits found since the last call to the distance bound the stop rule reads. */
    private void feedBound() {
        for (int i = fed; i < matches.size(); i++) {
            bound.offer(matches.get(i).distance());
        }
        fed = matches.size();
    }

    // 先移除任务再调用回调，返回扫描覆盖、未加载、提前停止和截断信息；找到一些结果不等于范围全部查完。
    private void finish(long now, boolean deadlineHit) {
        // Remove before calling foreign callbacks: they may enqueue/cancel searches or throw.
        JOBS.remove(this);
        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        // One line per search, and it has to carry everything a bug report needs: what was
        // asked, what came back, WHY it stopped, and what it cost. "She can't find X" is
        // answered by the stop reason plus the unloaded count, without a debug build.
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

    /** {@code iron_ore} for one target, {@code iron_ore+1} for a set — one log line, not a list. */
    private static String describe(Set<Block> targets) {
        Iterator<Block> it = targets.iterator();
        if (!it.hasNext()) return "nothing";
        String first = BuiltInRegistries.BLOCK.getKey(it.next()).getPath();
        return targets.size() > 1 ? first + "+" + (targets.size() - 1) : first;
    }
}
