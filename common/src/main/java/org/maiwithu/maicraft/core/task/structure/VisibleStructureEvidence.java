package org.maiwithu.maicraft.core.task.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/** 步行与飞行共用可见方块组合证据；每个候选先通过实时加载、视线和范围检查。 */
public final class VisibleStructureEvidence {
    public record Match(BlockPos position, Map<String,Integer> groupCounts, Map<String,Integer> blockCounts, int totalBlocks) {}
    public record Scan(Match match, boolean complete, int observedBlocks) {}
    private VisibleStructureEvidence() {}
    /** 飞机不能为一次远望阻塞整刻飞控；索引、可见性与候选匹配分刻续进，不丢掉尚未检查的候选。 */
    public static final class Incremental {
        private final StructureEvidenceProfiles.ResolvedProfile profile;
        private final Map<Long,BlockPos> queued = new LinkedHashMap<>();
        private final List<BlockPos> visible = new ArrayList<>();
        private List<BlockPos> hits;
        private BlockPos center;
        private int group, visibility, anchor;
        private boolean complete = true;
        public Incremental(StructureEvidenceProfiles.ResolvedProfile profile) { this.profile = profile; }
        public Scan tick(LocalPlayer player, int radius) {
            if (center == null) center = player.blockPosition().immutable();
            if (group < profile.groups().size()) {
                var query = TargetIndex.query(player.clientLevel, center, profile.groups().get(group).blocks(),
                        384, Math.max(1, (radius + 15) / 16), 128);
                query.hits().forEach(at -> queued.putIfAbsent(at.asLong(), at.immutable()));
                // 每组索引最多观察二十刻；未遍历完的区域保持未知，后续巡航轮次继续查询热索引。
                if (query.complete() || player.level().getGameTime() - groupStarted >= 20 && groupStarted >= 0) {
                    complete &= query.complete(); group++; groupStarted = -1;
                } else if (groupStarted < 0) groupStarted = player.level().getGameTime();
                return null;
            }
            if (hits == null) {
                hits = new ArrayList<>(queued.values());
                hits.sort(Comparator.comparingDouble(at -> at.distSqr(center)));
            }
            long until = System.nanoTime() + 1_000_000L;
            for (int count = 0; visibility < hits.size() && count < 8 && (count == 0 || System.nanoTime() < until); count++) {
                var at = hits.get(visibility++);
                if (Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ()) <= radius
                        && player.clientLevel.isLoaded(at) && profile.targetBlocks().contains(player.clientLevel.getBlockState(at).getBlock())
                        && ObservationVisibility.block(player, at)) visible.add(at);
            }
            if (visibility < hits.size()) return null;
            // 每次只试少数聚集中心；已看见的方块保留到本轮结算，避免高空速度让重复扫描永远从头开始。
            int end = Math.min(visible.size(), anchor + 4);
            var candidates = visible.subList(anchor, end);
            var found = match(profile, visible, at -> player.clientLevel.isLoaded(at)
                    ? player.clientLevel.getBlockState(at).getBlock() : Blocks.AIR, candidates::contains);
            anchor = end;
            return found != null || anchor >= visible.size() ? new Scan(found, complete, visible.size()) : null;
        }
        private long groupStarted = -1;
    }
    public static Scan scan(LocalPlayer player, StructureEvidenceProfiles.ResolvedProfile profile, int radius,
                            Predicate<BlockPos> included, Predicate<BlockPos> candidate) {
        Map<Long,BlockPos> merged = new LinkedHashMap<>(); boolean complete = true;
        for (var group : profile.groups()) {
            var result = TargetIndex.query(player.clientLevel, player.blockPosition(), group.blocks(), 384,
                    Math.max(1, (radius + 15) / 16), 128);
            complete &= result.complete();
            for (BlockPos hit : result.hits()) {
                if (!included.test(hit) || !player.clientLevel.isLoaded(hit)
                        || !profile.targetBlocks().contains(player.clientLevel.getBlockState(hit).getBlock())
                        || !ObservationVisibility.block(player, hit)) continue;
                merged.putIfAbsent(hit.asLong(), hit.immutable());
            }
        }
        var hits = new ArrayList<>(merged.values());
        hits.sort(Comparator.comparingDouble(at -> at.distSqr(player.blockPosition())));
        return new Scan(match(profile, hits, at -> player.clientLevel.getBlockState(at).getBlock(), candidate), complete, hits.size());
    }
    /** 一个方块可属于多个特征组，但总数量只数一次；同类方块必须在同一真实聚集半径内。 */
    static Match match(StructureEvidenceProfiles.ResolvedProfile profile, List<BlockPos> hits,
                       Function<BlockPos,Block> blockAt, Predicate<BlockPos> candidate) {
        double radiusSquared = (double) profile.profile().clusterRadius() * profile.profile().clusterRadius();
        for (BlockPos anchor : hits) {
            if (!candidate.test(anchor)) continue;
            var groups = new LinkedHashMap<String,Integer>(); var blocks = new LinkedHashMap<String,Integer>();
            int total = 0;
            for (BlockPos hit : hits) {
                if (hit.distSqr(anchor) > radiusSquared) continue;
                Block block = blockAt.apply(hit);
                // 分刻观察后被拆掉或变化的材料不再计入总数，避免用旧可见样本伪造当前组合。
                if (!profile.targetBlocks().contains(block)) continue;
                total++;
                blocks.merge(BuiltInRegistries.BLOCK.getKey(block).toString(), 1, Integer::sum);
                for (var group : profile.groups()) if (group.blocks().contains(block)) groups.merge(group.label(), 1, Integer::sum);
            }
            boolean all = true;
            for (var group : profile.groups()) {
                int count = groups.computeIfAbsent(group.label(), ignored -> 0);
                if (count < group.minimum()) all = false;
            }
            if (all && total >= profile.profile().minimumTotal())
                return new Match(anchor.immutable(), Map.copyOf(groups), Map.copyOf(blocks), total);
        }
        return null;
    }
}
