package org.maiwithu.maicraft.core.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 缓存当前客户端已加载方块的位置，供采矿等任务反复询问最近目标。
 * 新访问的区块段按需建索引，方块种类变化时更新；查询进度可以跨刻继续，不把一次预算用完当成没找到。
 */
public final class TargetIndex {

    private TargetIndex() {}

    /** 段内某目标超过该数即记"饱和",不枚举位置(4096 格的 1/16)。 */
    private static final int SATURATION = 256;
    /** 饱和标记(位置永远非负,-1 不会与真实位置冲突)。 */
    private static final short[] SATURATED = {-1};
    /** 驱逐清扫周期(tick)。 */
    private static final int EVICT_SWEEP_TICKS = 200;
    private static final long QUERY_NANOS_PER_TICK = 2_000_000L;
    private static final int MAX_PENDING_QUERIES = 32;
    private static final int COMPLETED_QUERY_TICKS = 20;
    private static long queryTick = Long.MIN_VALUE;
    private static long queryDeadline;

    /** 有任何维度有注册目标时为 true——方块变更钩子的最外层免费闸门。 */
    private static volatile boolean anyActive;
    private static final Map<ResourceKey<Level>, LevelIndex> INDEXES = new HashMap<>();
    private static int sweepTimer;

    /** 一个维度的索引。 */
    private static final class LevelIndex {
        /** 目标方块 → 注册计数(多个任务可共享同一目标)。 */
        final Reference2IntOpenHashMap<Block> targetRefs = new Reference2IntOpenHashMap<>();
        /** SectionPos.asLong → 条目。 */
        final Long2ObjectOpenHashMap<SectionEntry> sections = new Long2ObjectOpenHashMap<>();
        /** 目标集合的版本号:成员增减时自增,旧版本条目查询时懒重建。 */
        int version = 1;
        int activeRefs;
        long lastUseTick;
        final Map<QueryKey, QueryProgress> queries = new LinkedHashMap<>();
    }

    private record QueryKey(BlockPos center, List<Block> targets, int want, int radius,
                            Set<BlockPos> excluded) {}

    /** A cold query resumes at its next section instead of repeatedly walking its warm prefix. */
    private static final class QueryProgress {
        final int[] sectionOrder;
        final SearchGeometry.NearestPositions nearest;
        int ring, perimeterIndex, sectionIndex;
        boolean complete;
        long completedTick;

        QueryProgress(ClientLevel level, QueryKey key) {
            sectionOrder = SearchGeometry.sectionOrder(level.getMinSection(),
                    level.getMaxSection() - 1, SectionPos.blockToSectionCoord(key.center().getY()));
            nearest = new SearchGeometry.NearestPositions(key.center(), key.want(), key.excluded());
        }
    }

    /** 一个 section 的条目:该段内每种目标的打包位置(y<<8|z<<4|x),或饱和标记。 */
    // 每个区块段按方块种类保存位置；同种超过 256 个时只记饱和标志，查询时再现场读取，避免存大量重复坐标。
    private static final class SectionEntry {
        final int version;
        final LevelChunkSection source;
        final Reference2ObjectOpenHashMap<Block, short[]> hits = new Reference2ObjectOpenHashMap<>();

        SectionEntry(int version, LevelChunkSection source) {
            this.version = version;
            this.source = source;
        }

        void add(Block b, short packed) {
            short[] arr = hits.get(b);
            if (arr == SATURATED) {
                return;
            }
            if (arr == null) {
                hits.put(b, new short[]{packed});
                return;
            }
            if (arr.length + 1 > SATURATION) {
                hits.put(b, SATURATED);
                return;
            }
            short[] grown = new short[arr.length + 1];
            System.arraycopy(arr, 0, grown, 0, arr.length);
            grown[arr.length] = packed;
            hits.put(b, grown);
        }

        void remove(Block b, short packed) {
            short[] arr = hits.get(b);
            if (arr == null || arr == SATURATED) {
                return;   // 饱和段轻微高估无害:消费端取用时还会活世界复验
            }
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == packed) {
                    if (arr.length == 1) {
                        hits.remove(b);
                    } else {
                        short[] shrunk = new short[arr.length - 1];
                        System.arraycopy(arr, 0, shrunk, 0, i);
                        System.arraycopy(arr, i + 1, shrunk, i, arr.length - 1 - i);
                        hits.put(b, shrunk);
                    }
                    return;
                }
            }
        }
    }

    /** At most the requested nearest hits, in distance order; incomplete scans resume next tick. */
    public record Result(List<BlockPos> hits, boolean complete) {}

    // ==================== 注册 ====================

    /** 任务开始时登记其目标方块(计数式,可重入)。 */
    // 登记任务关心的方块类型并增加引用数；加入新类型时让旧段索引过期，下一次访问再补建。
    public static void register(ClientLevel level, Collection<Block> blocks) {
        LevelIndex idx = INDEXES.computeIfAbsent(level.dimension(), k -> new LevelIndex());
        boolean changed = false;
        for (Block b : blocks) {
            if (!idx.targetRefs.containsKey(b)) changed = true;
            idx.targetRefs.addTo(b, 1);
            idx.activeRefs++;
        }
        if (changed) {
            idx.version++;
            idx.queries.clear();
        }
        idx.lastUseTick = level.getGameTime();
        anyActive = true;
    }

    /** Release ownership; the next eviction sweep retires a cache unused for 200 ticks. */
    // 减少使用计数，但当前不从 targetRefs 删除零引用类型；只要整个维度仍在使用，这些旧类型会继续留着。
    public static void unregister(ClientLevel level, Collection<Block> blocks) {
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null) {
            return;
        }
        for (Block b : blocks) {
            if (idx.targetRefs.getInt(b) > 0) {
                idx.targetRefs.addTo(b, -1);
                idx.activeRefs--;
            }
        }
        // Keep a short-lived warm cache, including zero-reference target kinds. Planning probes
        // register/unregister between ticks and must be able to finish their pending scan.
        idx.lastUseTick = level.getGameTime();
        anyActive = !INDEXES.isEmpty();
    }

    // ==================== 供给:方块变更钩子 ====================

    /** Optional client observation hook for keeping an already-built section entry fresh. */
    // 客户端观察到关心的方块种类变化时更新索引；这也可能是客户端预测，使用位置前仍需核对实际世界。
    // 当前会清掉全部查询进度，没有按查询的目标种类或范围筛选；零引用旧类型也可触发（A67）。
    public static void onBlockChange(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!anyActive) {
            return;
        }
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null) {
            return;
        }
        Block ob = oldState.getBlock();
        Block nb = newState.getBlock();
        boolean oldT = idx.targetRefs.containsKey(ob);
        boolean newT = idx.targetRefs.containsKey(nb);
        if ((!oldT && !newT) || ob == nb) {
            return;
        }
        idx.queries.clear();
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
        SectionEntry e = idx.sections.get(key);
        if (e == null || e.version != idx.version) {
            return;   // 未建/已过期:下次查询重建时读的就是新状态
        }
        short packed = pack(pos);
        if (oldT) {
            e.remove(ob, packed);
        }
        if (newT) {
            e.add(nb, packed);
        }
    }

    // ==================== 查询 ====================

    /**
     * 从 {@code center} 按 chebyshev 区块环由近及远收集 {@code targets} 的位置。哪一节先看、
     * 什么时候可以不看了,判据在 {@link SearchGeometry} ——和现扫的 {@link BlockSearch} 同一份,
     * 同一片地不会给出两种"最近"。收工是精确的:攒够的 {@code want} 个已经比下一环最近的可能
     * 还近才停。
     *
     * <p>只读索引;未建条目就地构建,每次调用最多构建 {@code buildBudget} 个 section(预算耗尽
     * 返回 {@code complete=false},调用方稍后再查)。所有查询共享每 tick 2ms 墙钟预算，
     * 包括热索引遍历和饱和段取位；游标跨 tick 续进，避免反复遍历前缀饿死冷段。未加载区块
     * 跳过——索引只回答已加载世界。
     */
    public static Result query(ClientLevel level, BlockPos center, Collection<Block> targets,
                               int want, int maxChunkRadius, int buildBudget) {
        return query(level, center, targets, want, maxChunkRadius, buildBudget, Set.of());
    }

    /** Exclusions participate in the nearest selection, not after truncating the result window. */
    // 按起点、目标、数量、半径和排除位置复用扫描进度；一刻最多约两毫秒，未完成时返回当前已找到的部分。
    public static Result query(ClientLevel level, BlockPos center, Collection<Block> targets,
                               int want, int maxChunkRadius, int buildBudget, Set<BlockPos> excluded) {
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null || targets.isEmpty() || want <= 0) {
            return new Result(List.of(), true);
        }
        long tick = level.getGameTime();
        if (queryTick != tick) {
            queryTick = tick;
            queryDeadline = System.nanoTime() + QUERY_NANOS_PER_TICK;
        }
        idx.lastUseTick = tick;
        QueryKey key = new QueryKey(center.immutable(), List.copyOf(targets), want,
                Math.max(0, maxChunkRadius), excluded.stream().map(BlockPos::immutable)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        QueryProgress progress = idx.queries.get(key);
        if (progress != null && progress.complete
                && (tick < progress.completedTick || tick - progress.completedTick >= COMPLETED_QUERY_TICKS)) {
            idx.queries.remove(key);
            progress = null;
        }
        if (progress == null) {
            while (idx.queries.size() >= MAX_PENDING_QUERIES) {
                idx.queries.remove(idx.queries.keySet().iterator().next());
            }
            progress = new QueryProgress(level, key);
            idx.queries.put(key, progress);
        }
        int centerCx = SectionPos.blockToSectionCoord(center.getX());
        int centerCz = SectionPos.blockToSectionCoord(center.getZ());
        int minSection = level.getMinSection();
        int budget = Math.max(1, buildBudget);
        while (!progress.complete && System.nanoTime() < queryDeadline) {
            if (progress.perimeterIndex >= RingSpiral.perimeter(progress.ring)) {
                if (progress.nearest.canStopAfterRing(progress.ring)
                        || ++progress.ring > key.radius()) {
                    progress.complete = true;
                    progress.completedTick = tick;
                    break;
                }
                progress.perimeterIndex = 0;
            }
            int[] offset = RingSpiral.offset(progress.ring, progress.perimeterIndex);
            int cx = centerCx + offset[0];
            int cz = centerCz + offset[1];
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null || progress.sectionIndex >= progress.sectionOrder.length) {
                progress.perimeterIndex++;
                progress.sectionIndex = 0;
                continue;
            }
            int sy = progress.sectionOrder[progress.sectionIndex];
            LevelChunkSection section = chunk.getSections()[sy - minSection];
            long sectionKey = SectionPos.asLong(cx, sy, cz);
            SectionEntry entry = idx.sections.get(sectionKey);
            if (entry == null || entry.version != idx.version || entry.source != section) {
                if (budget-- <= 0) break;
                entry = build(section, idx);
                idx.sections.put(sectionKey, entry);
            }
            collect(entry, section, cx, sy, cz, targets, progress.nearest);
            progress.sectionIndex++;
        }
        return new Result(progress.nearest.sorted(), progress.complete);
    }

    /** palette 预筛:这个 section 一定不含任何目标(纯空气,或调色板里就没有)。 */
    private static boolean triviallyEmpty(LevelChunkSection section, LevelIndex idx) {
        var targets = idx.targetRefs.keySet();
        return section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> targets.contains(state.getBlock()));
    }

    /** 构建一个 section 的条目:palette 预筛 → 一趟计数定饱和 → 一趟收位。 */
    // 先统计这一段包含哪些目标类型；稀少类型保存具体位置，很多的类型用饱和标志。
    private static SectionEntry build(LevelChunkSection section, LevelIndex idx) {
        SectionEntry e = new SectionEntry(idx.version, section);
        if (triviallyEmpty(section, idx)) {
            return e;
        }
        var targets = idx.targetRefs.keySet();
        Reference2IntOpenHashMap<Block> counts = new Reference2IntOpenHashMap<>();
        section.getStates().count((state, n) -> {
            Block b = state.getBlock();
            if (targets.contains(b)) {
                counts.addTo(b, n);
            }
        });
        if (counts.isEmpty()) {
            return e;   // maybeHas 的假阳性(GlobalPalette 恒真)
        }
        Reference2ObjectOpenHashMap<Block, ShortArrayList> collecting = new Reference2ObjectOpenHashMap<>();
        for (var it = counts.reference2IntEntrySet().fastIterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getIntValue() > SATURATION) {
                e.hits.put(en.getKey(), SATURATED);
            } else {
                collecting.put(en.getKey(), new ShortArrayList(en.getIntValue()));
            }
        }
        if (!collecting.isEmpty()) {
            var states = section.getStates();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        ShortArrayList list = collecting.get(states.get(x, y, z).getBlock());
                        if (list != null) {
                            list.add((short) (y << 8 | z << 4 | x));
                        }
                    }
                }
            }
            for (var it = collecting.reference2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var en = it.next();
                e.hits.put(en.getKey(), en.getValue().toShortArray());
            }
        }
        return e;
    }

    /** 把条目中所请求目标的位置追加进 {@code out};饱和目标对该一个 section 现场取位。 */
    // 普通条目直接解码位置；饱和条目当场遍历该段，再交给最近位置容器排序。
    private static void collect(SectionEntry e, LevelChunkSection section,
                                int cx, int sy, int cz, Collection<Block> targets,
                                SearchGeometry.NearestPositions nearest) {
        if (e.hits.isEmpty()) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(cx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(cz);
        for (Block b : targets) {
            short[] arr = e.hits.get(b);
            if (arr == null) {
                continue;
            }
            if (arr == SATURATED) {
                // Dense cells are compared by distance too; y/z/x iteration order is not proximity.
                var states = section.getStates();
                BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (states.get(x, y, z).getBlock() == b) {
                                nearest.offer(cell.set(baseX | x, baseY + y, baseZ | z));
                            }
                        }
                    }
                }
                continue;
            }
            for (short p : arr) {
                nearest.offer(new BlockPos(baseX | (p & 15), baseY + (p >> 8 & 15), baseZ | (p >> 4 & 15)));
            }
        }
    }

    // ==================== 生命周期 ====================

    /** Called from END_CLIENT_TICK; periodically evicts entries for chunks no longer loaded. */
    // 周期性清理没人使用的维度索引与已卸载区块；活动索引的零引用类型目前不在这里单独清理。
    public static void clientTick(ClientLevel level) {
        if (INDEXES.isEmpty() || ++sweepTimer < EVICT_SWEEP_TICKS) {
            return;
        }
        sweepTimer = 0;
        INDEXES.entrySet().removeIf(entry -> entry.getValue().activeRefs == 0
                && level.getGameTime() - entry.getValue().lastUseTick >= EVICT_SWEEP_TICKS);
        anyActive = !INDEXES.isEmpty();
        for (Map.Entry<ResourceKey<Level>, LevelIndex> entry : INDEXES.entrySet()) {
            if (!entry.getKey().equals(level.dimension())) {
                entry.getValue().sections.clear();
                continue;
            }
            long lastChunkKey = Long.MIN_VALUE;
            boolean lastLoaded = false;
            var iterator = entry.getValue().sections.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                long key = iterator.next().getLongKey();
                int chunkX = SectionPos.x(key);
                int chunkZ = SectionPos.z(key);
                long chunkKey = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
                if (chunkKey != lastChunkKey) {
                    lastChunkKey = chunkKey;
                    lastLoaded = level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
                }
                if (!lastLoaded) {
                    iterator.remove();
                }
            }
        }
    }

    /** Clear all observations when the local body or world disappears. */
    // 世界会话结束时清空全部索引和查询时钟；客户端运行时负责在相应边界调用。
    public static void dropAll() {
        INDEXES.clear();
        anyActive = false;
        sweepTimer = 0;
        queryTick = Long.MIN_VALUE;
    }

    private static short pack(BlockPos pos) {
        return (short) ((pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15));
    }
}
