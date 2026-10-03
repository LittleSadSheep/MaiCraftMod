// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 可见岩浆证据先按同层连通分池，再复用浇筑模板检查岸线；只读报告，绝不因预测结果修改世界。 */
public final class PortalLavaPoolSurvey {
    private final ClientLevel world;
    private final BlockPos origin;
    private final Set<BlockPos> sources = new LinkedHashSet<>();
    private final Set<BlockPos> remaining = new LinkedHashSet<>();
    private final Set<BlockPos> pool = new LinkedHashSet<>();
    private final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private Iterator<BlockPos> sites;
    private BlockPos nearest, nearestMatchingSource;
    private Candidate preferred;
    private int flowing, submerged, longestBank, matchingPools;
    private long processed;
    private boolean started, complete;

    private record Candidate(NetherPortalCastingLayout layout, int fill, int consumed, int remaining) {
        boolean reserveObserved() { return remaining >= PortalCastingTerrain.MINIMUM_REMAINING_SOURCES; }
        boolean betterThan(Candidate other) {
            return other == null || reserveObserved() && !other.reserveObserved()
                    || reserveObserved() == other.reserveObserved()
                    && (consumed < other.consumed || consumed == other.consumed && fill < other.fill);
        }
    }

    public PortalLavaPoolSurvey(ClientLevel world, BlockPos origin) {
        this.world = world; this.origin = origin.immutable();
    }

    /** 调用方必须先确认角色看得见；流水和被上层岩浆覆盖的源格单列，不混入表层可取桶余量。 */
    public void observeVisible(BlockPos at, BlockState state) {
        if (started || !state.is(Blocks.LAVA)) return;
        if (!state.getFluidState().isSource()) { flowing++; return; }
        var above = PortalPreparationSite.read(world, at.above());
        if (above == null || above.is(Blocks.LAVA)) { submerged++; return; }
        sources.add(at.immutable());
    }

    /** 搜索完成后按时间片扩展连通面和检查施工朝向；大池不会在一次结果序列化时卡住游戏。 */
    public void advance(int budget, long nanos) {
        if (!started) { remaining.addAll(sources); started = true; }
        long until = System.nanoTime() + nanos;
        while (!complete && budget-- > 0 && System.nanoTime() < until) {
            processed++;
            if (!pending.isEmpty()) {
                BlockPos at = pending.removeFirst(); pool.add(at);
                if (nearest == null || at.distSqr(origin) < nearest.distSqr(origin)) nearest = at;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos next = at.relative(side);
                    if (remaining.remove(next)) pending.add(next);
                }
            } else if (sites != null && sites.hasNext()) inspect(sites.next());
            else if (sites == null && !pool.isEmpty()) sites = pool.iterator();
            else {
                if (!pool.isEmpty()) {
                    // 池面分析完整后才计为用途匹配；邻近孤立源或另一片池子的余量不能替这个池子凑数。
                    if (preferred != null && preferred.reserveObserved()) {
                        matchingPools++;
                        // 用途查询的坐标必须属于真正符合条件的池子，不能把更近的孤立源交给后续移动能力。
                        if (nearestMatchingSource == null || nearest.distSqr(origin) < nearestMatchingSource.distSqr(origin))
                            nearestMatchingSource = nearest;
                    }
                    results.add(poolFacts()); pool.clear();
                }
                sites = null; nearest = null; preferred = null; longestBank = 0;
                if (remaining.isEmpty()) { complete = true; continue; }
                BlockPos seed = remaining.iterator().next(); remaining.remove(seed); pending.add(seed);
            }
        }
    }

    private void inspect(BlockPos seed) {
        for (Direction shore : Direction.Plane.HORIZONTAL) {
            Direction across = shore.getClockWise();
            // 只从已观察岸段的起点数长度，断开的源格及遮住的格子不会拼成一条虚构的完整岸。
            if (bank(seed, shore) && !bank(seed.relative(across.getOpposite()), shore)) {
                int length = 0;
                for (BlockPos at = seed; bank(at, shore); at = at.relative(across)) length++;
                longestBank = Math.max(longestBank, length);
            }
            var layout = new NetherPortalCastingLayout(seed, shore);
            boolean rowObserved = true;
            for (int x = -1; x <= 2; x++) rowObserved &= pool.contains(layout.cell(x, 0, 0));
            if (!rowObserved || !PortalCastingSurvey.atShore(world, layout)) continue;
            var fill = PortalCastingTerrain.missingPlatform(world, layout);
            int consumed = (int) fill.stream().filter(pool::contains).count();
            // 只从可见源格中扣除填岸消耗；隐藏的池深和旁支既不增加余量，也不被断言不存在。
            var candidate = new Candidate(layout, fill.size(), consumed, pool.size() - consumed);
            if (candidate.betterThan(preferred)) preferred = candidate;
        }
    }

    private boolean bank(BlockPos at, Direction shore) {
        return pool.contains(at) && PortalCastingTerrain.sturdy(world, at.relative(shore));
    }

    private Map<String, Object> poolFacts() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("pool_index", results.size() + 1);
        facts.put("observed_surface_source_count", pool.size());
        facts.put("nearest_distance", Math.round(Math.sqrt(nearest.distSqr(origin)) * 10.0) / 10.0);
        facts.put("vertical_offset", nearest.getY() - origin.getY());
        int dx = nearest.getX() - origin.getX(), dz = nearest.getZ() - origin.getZ();
        facts.put("direction", dx == 0 && dz == 0 ? "here"
                : (dz < 0 ? "north" : dz > 0 ? "south" : "") + (dx < 0 ? "west" : dx > 0 ? "east" : ""));
        facts.put("longest_observed_straight_bank", longestBank);
        facts.put("casting_start_row_observed", preferred != null);
        facts.put("matches_portal_casting", preferred != null && preferred.reserveObserved());
        if (preferred != null) {
            // 公共回执保留语义方位与整形预算；实际方块坐标仍由 Mod 内部的浇筑能力解析。
            facts.put("candidate", Map.of("shore_direction", preferred.layout.shore().getSerializedName(),
                    "platform_blocks_needed", preferred.fill, "observed_sources_consumed_by_fill", preferred.consumed,
                    "remaining_sources_lower_bound", preferred.remaining, "reserve_observed", preferred.reserveObserved()));
        }
        return facts;
    }

    /** 回执中的候选仅说明已观察资源和模板几何，寻路、取桶与流体反应仍需真实执行验证。 */
    public Map<String, Object> facts() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("scope", "connected directly visible surface sources at the same height; hidden connections and depth unknown");
        facts.put("analysis_complete", complete); facts.put("observed_surface_sources", sources.size());
        facts.put("flowing_lava_observed", flowing); facts.put("submerged_or_unknown_surface_sources", submerged);
        facts.put("minimum_remaining_sources", PortalCastingTerrain.MINIMUM_REMAINING_SOURCES);
        facts.put("pools", List.copyOf(results));
        facts.put("matching_portal_casting_pools", matchingPools);
        facts.put("candidate_scope", "loaded template geometry and visible resource budget; path, bucket access and native fluid outcomes unverified");
        if (!complete && !pool.isEmpty()) facts.put("pending_pool_observed_sources", pool.size());
        return facts;
    }

    public boolean complete() { return complete; }
    public int matchingPools() { return matchingPools; }
    public BlockPos nearestMatchingSource() { return nearestMatchingSource; }
    public long processed() { return processed; }
}
