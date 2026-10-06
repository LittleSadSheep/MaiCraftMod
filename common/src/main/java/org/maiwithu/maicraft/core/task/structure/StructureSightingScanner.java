// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.structure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/**
 * 联合锚点扫描协调器：所有画像第一组的签名方块并成一份锚点清单，一次索引查询找全，
 * 再按「方块 → 第一组含它的画像」反查，只对锚点命中的画像做后续工作；
 * 其余分组按画像逐份查询后过可见性，最后对每个命中画像做聚类判定。
 * 其余分组不走全局并集查询：索引查询的最近窗口有数量上限，稠密常见方块（房屋木板等）会
 * 把远处稀疏的决定性证据挤出窗口，画像分组下限就凑不齐；按画像逐份查询后，窗口只装该画像
 * 自己关心的方块，稀疏证据不被挤占。
 * 证据画像只能证明方块组合符合特征，不能区分天然结构与人为仿建；发现不去重不落账，归调用方处理。
 */
public final class StructureSightingScanner {

    /** canonicalId 取画像本体编号而非请求别名；锚点即聚类判定成立的位置。 */
    public record Sighting(String canonicalId, BlockPos anchor,
            Map<String, Integer> groupCounts, Map<String, Integer> blockCounts, int totalBlocks) {}

    private enum Phase { ANCHOR_INDEX, ANCHOR_VISIBILITY, GROUP_INDEX, MATCH }

    private static final int ANCHOR_WANT = 192;
    private static final int GROUP_WANT = 64;
    private static final int INDEX_BUILD_BUDGET = 384;
    private static final long VISIBILITY_WINDOW_NANOS = 1_000_000L;
    private static final int MAX_VISIBILITY_PER_TICK = 16;
    private static final int ANCHOR_WINDOW = 4;
    /**
     * 索引阶段身体偏离本轮中心超过覆盖半径才换址重开：换轮心会换掉 TargetIndex 查询键，
     * 在途游标与已建索引段条目全部作废重走，冷索引在真实世界的建段开销下永远走不完一轮；
     * 覆盖半径内继续旧轮，锚定的仍是同一片已投索引的区域，轮次自然结算后新轮再锚新位置。
     */
    private static final double RESTART_FRACTION = 1.0;

    private final List<StructureEvidenceProfiles.ResolvedProfile> profiles;
    private final int radius;
    private final int chunkRadius;
    private final Set<Block> anchorBlocks;
    private final Set<Block> watchedBlocks;
    /** 签名方块 → 第一组含它的画像下标；反查依据是第一组，命中别的组的方块不构成该画像的锚点。 */
    private final Map<Block, List<Integer>> anchorOwners = new LinkedHashMap<>();

    private Phase phase = Phase.ANCHOR_INDEX;
    /** TargetIndex 只为注册过的方块建段条目；未注册时查询直接空收工，锚点命中永远为零。 */
    private boolean registered;
    private ClientLevel registeredLevel;
    /** 本轮已投入进度且聚类判定未结算；调用方据此延长观察驻留，不把在途轮丢给路点移动。 */
    private boolean roundActive;
    private BlockPos roundCenter;
    private final Map<Long, BlockPos> queued = new LinkedHashMap<>();
    private List<BlockPos> hits;
    private final List<BlockPos> visible = new ArrayList<>();
    private int scanIndex;
    /** 锚点命中的画像下标，按序逐份查其余分组；无锚点命中的画像不产生任何额外查询。 */
    private List<Integer> activeProfiles;
    private int activeCursor;
    private int activeGroup;
    private List<List<BlockPos>> profileAnchors;
    private List<BlockPos> groupHits;
    /** 分组可见性计数按画像分段累加，visible 本身跨画像累计，直接取 size 会重复计数。 */
    private int visibleMark;
    private int[] cursors;
    private final boolean[] done;
    private int remaining;

    // 逐相诊断计数：实机零落账时回执直接指出断在哪一相，不再靠排除法定位。
    private long roundsStarted;
    private long restarts;
    private long anchorHitsCollected;
    private long anchorVisibleCount;
    private long groupQueriesCompleted;
    private long groupHitsCollected;
    private long groupVisibleCount;
    private long sightingsDelivered;
    private final long[] phaseTicks = new long[Phase.values().length];

    /** 入参由调用方按维度过滤；别名共享同一份画像时只留一份，同一 canonicalId 重复扫描是纯浪费。 */
    public StructureSightingScanner(List<StructureEvidenceProfiles.ResolvedProfile> resolved, int radius) {
        Map<String, StructureEvidenceProfiles.ResolvedProfile> unique = new LinkedHashMap<>();
        for (var profile : resolved) unique.putIfAbsent(profile.profile().canonicalId(), profile);
        this.profiles = List.copyOf(unique.values());
        this.radius = radius;
        this.chunkRadius = Math.max(1, (radius + 15) / 16);
        Set<Block> anchors = new LinkedHashSet<>();
        Set<Block> watched = new LinkedHashSet<>();
        for (int i = 0; i < profiles.size(); i++) {
            var profile = profiles.get(i);
            for (Block block : profile.groups().get(0).blocks()) {
                anchors.add(block);
                watched.add(block);
                anchorOwners.computeIfAbsent(block, ignored -> new ArrayList<>()).add(i);
            }
            for (int g = 1; g < profile.groups().size(); g++) {
                watched.addAll(profile.groups().get(g).blocks());
            }
        }
        this.anchorBlocks = Set.copyOf(anchors);
        this.watchedBlocks = Set.copyOf(watched);
        this.done = new boolean[profiles.size()];
        this.remaining = profiles.size();
    }

    public boolean isEmpty() { return profiles.isEmpty(); }

    /** 归还 TargetIndex 引用计数；任务收尾必须调用，热缓存由索引的闲置清扫周期回收。 */
    public void release() {
        if (registered) {
            TargetIndex.unregister(registeredLevel, watchedBlocks);
            registered = false;
        }
    }

    /** 观察驻留据此决定是否继续等待本轮结算；结算后为 false，路点移动才允许开始。 */
    public boolean roundInProgress() { return roundActive; }

    /** 逐相诊断计数快照：相名 → 已耗刻数，外加各相产出计数，供回执与日志定位断相。 */
    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Phase phase : Phase.values()) {
            long ticks = phaseTicks[phase.ordinal()];
            if (ticks > 0) out.put("ticks_" + phase.name().toLowerCase(), ticks);
        }
        out.put("rounds_started", roundsStarted);
        out.put("restarts", restarts);
        out.put("anchor_hits", anchorHitsCollected);
        out.put("anchor_visible", anchorVisibleCount);
        out.put("group_queries_completed", groupQueriesCompleted);
        out.put("group_hits", groupHitsCollected);
        out.put("group_visible", groupVisibleCount);
        out.put("sightings", sightingsDelivered);
        return out;
    }

    /**
     * 每刻至多推进一步：锚点查询、可见性与逐画像分组查询分刻续进，匹配阶段每画像每刻只试一小窗锚点。
     * 单刻工作量有封顶，一轮在多刻内必然走完；返回本刻完成判定的 sighting，可能为空。
     * 轮次结束后下一刻从身体当前位置开新轮。
     */
    public List<Sighting> tick(LocalPlayer player) {
        List<Sighting> out = new ArrayList<>();
        if (profiles.isEmpty()) return out;
        ClientLevel level = player.clientLevel;
        if (level == null) return out;
        phaseTicks[phase.ordinal()]++;
        if (roundCenter == null) {
            roundCenter = player.blockPosition().immutable();
            roundsStarted++;
        }
        if (!registered) {
            TargetIndex.register(level, watchedBlocks);
            registered = true;
            registeredLevel = level;
        }
        roundActive = true;
        if (phase == Phase.ANCHOR_INDEX) {
            double dx = player.getX() - roundCenter.getX();
            double dz = player.getZ() - roundCenter.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > radius * RESTART_FRACTION) restartRound(player);
        }
        switch (phase) {
            case ANCHOR_INDEX -> {
                var result = TargetIndex.query(level, roundCenter, anchorBlocks,
                        ANCHOR_WANT, chunkRadius, INDEX_BUILD_BUDGET);
                result.hits().forEach(at -> queued.putIfAbsent(at.asLong(), at.immutable()));
                if (result.complete()) beginAnchorVisibility();
            }
            case ANCHOR_VISIBILITY -> {
                collectVisible(player, level, hits);
                if (scanIndex >= hits.size()) beginGroupIndex(level);
            }
            case GROUP_INDEX -> tickGroupIndex(player, level);
            case MATCH -> tickMatch(player, level, out);
        }
        return out;
    }

    private void beginAnchorVisibility() {
        anchorHitsCollected += queued.size();
        // 锚点按距离排序，近处锚点先受审；同方块同位置天然去重。
        hits = new ArrayList<>(queued.values());
        hits.sort(Comparator.comparingDouble(at -> at.distSqr(roundCenter)));
        scanIndex = 0;
        phase = Phase.ANCHOR_VISIBILITY;
    }

    private void beginGroupIndex(ClientLevel level) {
        anchorVisibleCount += visible.size();
        profileAnchors = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) profileAnchors.add(new ArrayList<>());
        for (BlockPos at : visible) {
            if (!level.isLoaded(at)) continue;
            Block block = level.getBlockState(at).getBlock();
            for (int index : anchorOwners.getOrDefault(block, List.of()))
                profileAnchors.get(index).add(at);
        }
        activeProfiles = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            if (!profileAnchors.get(i).isEmpty()) activeProfiles.add(i);
        }
        activeCursor = 0;
        activeGroup = 1;
        visibleMark = visible.size();
        queued.clear();
        hits = null;
        scanIndex = 0;
        cursors = new int[profiles.size()];
        Arrays.fill(done, false);
        remaining = activeProfiles.size();
        phase = activeProfiles.isEmpty() ? Phase.MATCH : Phase.GROUP_INDEX;
    }

    /** 每刻至多推进一份画像的一个分组：先索引后可见性，两步都在本刻内顺序走。 */
    private void tickGroupIndex(LocalPlayer player, ClientLevel level) {
        int profileIndex = activeProfiles.get(activeCursor);
        var profile = profiles.get(profileIndex);
        if (activeGroup >= profile.groups().size()) {
            finishActiveProfile();
            return;
        }
        if (hits == null) {
            var result = TargetIndex.query(level, roundCenter, profile.groups().get(activeGroup).blocks(),
                    GROUP_WANT, chunkRadius, INDEX_BUILD_BUDGET);
            result.hits().forEach(at -> queued.putIfAbsent(at.asLong(), at.immutable()));
            if (!result.complete()) return;
            groupQueriesCompleted++;
            groupHitsCollected += queued.size();
            groupHits = new ArrayList<>(queued.values());
            queued.clear();
            groupHits.sort(Comparator.comparingDouble(at -> at.distSqr(roundCenter)));
            hits = groupHits;
            scanIndex = 0;
        }
        collectVisible(player, level, hits);
        if (scanIndex < hits.size()) return;
        hits = null;
        groupHits = null;
        activeGroup++;
        if (activeGroup < profile.groups().size()) return;
        finishActiveProfile();
    }

    /** 一份画像的分组全部查完：分段累计本画像贡献的可见方块，再推进到下一份。 */
    private void finishActiveProfile() {
        groupVisibleCount += visible.size() - visibleMark;
        visibleMark = visible.size();
        activeCursor++;
        activeGroup = 1;
        if (activeCursor < activeProfiles.size()) return;
        phase = Phase.MATCH;
    }

    private void tickMatch(LocalPlayer player, ClientLevel level, List<Sighting> out) {
        for (int slot = 0; slot < activeProfiles.size(); slot++) {
            int i = activeProfiles.get(slot);
            if (done[i]) continue;
            Sighting sighting = matchNextWindow(level, i);
            if (sighting != null) {
                done[i] = true;
                remaining--;
                sightingsDelivered++;
                out.add(sighting);
            } else if (cursors[i] >= profileAnchors.get(i).size()) {
                done[i] = true;
                remaining--;
            }
        }
        if (remaining == 0) restartRound(player);
    }

    private void collectVisible(LocalPlayer player, ClientLevel level, List<BlockPos> source) {
        long until = System.nanoTime() + VISIBILITY_WINDOW_NANOS;
        for (int count = 0; scanIndex < source.size() && count < MAX_VISIBILITY_PER_TICK
                && (count == 0 || System.nanoTime() < until); count++) {
            BlockPos at = source.get(scanIndex++);
            if (Math.hypot(at.getX() - roundCenter.getX(), at.getZ() - roundCenter.getZ()) > radius) continue;
            if (!level.isLoaded(at)) continue;
            if (!watchedBlocks.contains(level.getBlockState(at).getBlock())) continue;
            if (!ObservationVisibility.block(player, at)) continue;
            visible.add(at.immutable());
        }
    }

    private Sighting matchNextWindow(ClientLevel level, int index) {
        var anchors = profileAnchors.get(index);
        int from = cursors[index];
        if (from >= anchors.size()) return null;
        int to = Math.min(anchors.size(), from + ANCHOR_WINDOW);
        Set<BlockPos> window = Set.copyOf(anchors.subList(from, to));
        cursors[index] = to;
        var profile = profiles.get(index);
        // 匹配内部会按当前世界状态重读方块并重验画像全集，可见期与匹配期之间被拆掉的材料不计入。
        var match = VisibleStructureEvidence.match(profile, visible, blockAt(level), window::contains);
        if (match == null) return null;
        return new Sighting(profile.profile().canonicalId(), match.position(),
                match.groupCounts(), match.blockCounts(), match.totalBlocks());
    }

    private static Function<BlockPos, Block> blockAt(ClientLevel level) {
        return at -> level.isLoaded(at) ? level.getBlockState(at).getBlock() : Blocks.AIR;
    }

    /** 一轮结束后清空现场；新轮锚在新位置，重复发现由调用方按簇半径去重。 */
    private void restartRound(LocalPlayer player) {
        phase = Phase.ANCHOR_INDEX;
        roundActive = false;
        roundCenter = player.blockPosition().immutable();
        roundsStarted++;
        restarts++;
        queued.clear();
        hits = null;
        visible.clear();
        visibleMark = 0;
        scanIndex = 0;
        activeProfiles = null;
        activeCursor = 0;
        activeGroup = 1;
        groupHits = null;
        profileAnchors = null;
        cursors = null;
        Arrays.fill(done, false);
        remaining = profiles.size();
    }
}
