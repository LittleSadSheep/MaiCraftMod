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
 * 再按「方块 → 第一组含它的画像」反查，只对有锚点命中的画像做聚类判定；第二份查询拿其余组方块并集供聚类计数。
 * 证据画像只能证明方块组合符合特征，不能区分天然结构与人为仿建；发现不去重不落账，归调用方处理。
 */
public final class StructureSightingScanner {

    /** canonicalId 取画像本体编号而非请求别名；锚点即聚类判定成立的位置。 */
    public record Sighting(String canonicalId, BlockPos anchor,
            Map<String, Integer> groupCounts, Map<String, Integer> blockCounts, int totalBlocks) {}

    private enum Phase { ANCHOR_INDEX, MASS_INDEX, VISIBILITY, MATCH }

    private static final int INDEX_WANT = 192;
    private static final int INDEX_BUILD_BUDGET = 384;
    private static final long VISIBILITY_WINDOW_NANOS = 1_000_000L;
    private static final int MAX_VISIBILITY_PER_TICK = 16;
    private static final int ANCHOR_WINDOW = 4;
    /** 索引阶段身体偏离本轮中心超过半径一半时，旧轮证据还没开始积累，直接换址重开不亏。 */
    private static final double RESTART_FRACTION = 0.5;

    private final List<StructureEvidenceProfiles.ResolvedProfile> profiles;
    private final int radius;
    private final int chunkRadius;
    private final Set<Block> anchorBlocks;
    private final Set<Block> massBlocks;
    private final Set<Block> watchedBlocks;
    /** 签名方块 → 第一组含它的画像下标；反查依据是第一组，命中别的组的方块不构成该画像的锚点。 */
    private final Map<Block, List<Integer>> anchorOwners = new LinkedHashMap<>();

    private Phase phase = Phase.ANCHOR_INDEX;
    private BlockPos roundCenter;
    private final Map<Long, BlockPos> queued = new LinkedHashMap<>();
    private List<BlockPos> hits;
    private final List<BlockPos> visible = new ArrayList<>();
    private int scanIndex;
    private List<List<BlockPos>> profileAnchors;
    private int[] cursors;
    private final boolean[] done;
    private int remaining;

    /** 入参由调用方按维度过滤；别名共享同一份画像时只留一份，同一 canonicalId 重复扫描是纯浪费。 */
    public StructureSightingScanner(List<StructureEvidenceProfiles.ResolvedProfile> resolved, int radius) {
        Map<String, StructureEvidenceProfiles.ResolvedProfile> unique = new LinkedHashMap<>();
        for (var profile : resolved) unique.putIfAbsent(profile.profile().canonicalId(), profile);
        this.profiles = List.copyOf(unique.values());
        this.radius = radius;
        this.chunkRadius = Math.max(1, (radius + 15) / 16);
        Set<Block> anchors = new LinkedHashSet<>();
        Set<Block> mass = new LinkedHashSet<>();
        Set<Block> watched = new LinkedHashSet<>();
        for (int i = 0; i < profiles.size(); i++) {
            var profile = profiles.get(i);
            for (Block block : profile.groups().get(0).blocks()) {
                anchors.add(block);
                watched.add(block);
                anchorOwners.computeIfAbsent(block, ignored -> new ArrayList<>()).add(i);
            }
            for (int g = 1; g < profile.groups().size(); g++) {
                for (Block block : profile.groups().get(g).blocks()) {
                    mass.add(block);
                    watched.add(block);
                }
            }
        }
        this.anchorBlocks = Set.copyOf(anchors);
        this.massBlocks = Set.copyOf(mass);
        this.watchedBlocks = Set.copyOf(watched);
        this.done = new boolean[profiles.size()];
        this.remaining = profiles.size();
    }

    public boolean isEmpty() { return profiles.isEmpty(); }

    /**
     * 每刻至多推进一步：两条索引查询共用 TargetIndex 的跨刻游标，可见性按墙钟窗口限量，
     * 匹配阶段每画像每刻只试一小窗锚点。单刻工作量有封顶，一轮在多刻内必然走完；
     * 返回本刻完成判定的 sighting，可能为空。轮次结束后下一刻从身体当前位置开新轮。
     */
    public List<Sighting> tick(LocalPlayer player) {
        List<Sighting> out = new ArrayList<>();
        if (profiles.isEmpty()) return out;
        ClientLevel level = player.clientLevel;
        if (level == null) return out;
        if (roundCenter == null) roundCenter = player.blockPosition().immutable();
        if (phase == Phase.ANCHOR_INDEX || phase == Phase.MASS_INDEX) {
            double dx = player.getX() - roundCenter.getX();
            double dz = player.getZ() - roundCenter.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > radius * RESTART_FRACTION) restartRound(player);
        }
        switch (phase) {
            case ANCHOR_INDEX -> {
                if (TargetIndex.query(level, roundCenter, anchorBlocks,
                        INDEX_WANT, chunkRadius, INDEX_BUILD_BUDGET).complete()) {
                    phase = Phase.MASS_INDEX;
                }
            }
            case MASS_INDEX -> {
                var result = massBlocks.isEmpty()
                        ? new TargetIndex.Result(List.of(), true)
                        : TargetIndex.query(level, roundCenter, massBlocks,
                                INDEX_WANT, chunkRadius, INDEX_BUILD_BUDGET);
                if (result.complete()) beginMatchPreparation();
            }
            case VISIBILITY -> {
                collectVisible(player, level);
                if (scanIndex >= hits.size()) beginClusterMatching(level);
            }
            case MATCH -> {
                for (int i = 0; i < profiles.size(); i++) {
                    if (done[i]) continue;
                    Sighting sighting = matchNextWindow(level, i);
                    if (sighting != null) {
                        done[i] = true;
                        remaining--;
                        out.add(sighting);
                    } else if (cursors[i] >= profileAnchors.get(i).size()) {
                        done[i] = true;
                        remaining--;
                    }
                }
                if (remaining == 0) restartRound(player);
            }
        }
        return out;
    }

    private void beginMatchPreparation() {
        // 两份查询的命中都进同一队列；同方块同位置天然去重，距离排序让近处锚点先受审。
        hits = new ArrayList<>(queued.values());
        hits.sort(Comparator.comparingDouble(at -> at.distSqr(roundCenter)));
        scanIndex = 0;
        phase = Phase.VISIBILITY;
    }

    private void collectVisible(LocalPlayer player, ClientLevel level) {
        long until = System.nanoTime() + VISIBILITY_WINDOW_NANOS;
        for (int count = 0; scanIndex < hits.size() && count < MAX_VISIBILITY_PER_TICK
                && (count == 0 || System.nanoTime() < until); count++) {
            BlockPos at = hits.get(scanIndex++);
            if (Math.hypot(at.getX() - roundCenter.getX(), at.getZ() - roundCenter.getZ()) > radius) continue;
            if (!level.isLoaded(at)) continue;
            if (!watchedBlocks.contains(level.getBlockState(at).getBlock())) continue;
            if (!ObservationVisibility.block(player, at)) continue;
            visible.add(at.immutable());
        }
    }

    private void beginClusterMatching(ClientLevel level) {
        profileAnchors = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) profileAnchors.add(new ArrayList<>());
        for (BlockPos at : visible) {
            if (!level.isLoaded(at)) continue;
            Block block = level.getBlockState(at).getBlock();
            for (int index : anchorOwners.getOrDefault(block, List.of()))
                profileAnchors.get(index).add(at);
        }
        cursors = new int[profiles.size()];
        phase = Phase.MATCH;
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
        roundCenter = player.blockPosition().immutable();
        queued.clear();
        hits = null;
        visible.clear();
        scanIndex = 0;
        profileAnchors = null;
        cursors = null;
        Arrays.fill(done, false);
        remaining = profiles.size();
    }
}
