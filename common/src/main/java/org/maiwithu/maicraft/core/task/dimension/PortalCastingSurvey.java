// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/** 只在已加载范围寻找可取的源格和池岸；没有候选时返回观察事实，不凭空假定地下有岩浆。 */
final class PortalCastingSurvey implements AutoCloseable {
    record Source(BlockPos position, boolean complete, int observed) {}
    private static final List<Block> FLUIDS = List.of(Blocks.LAVA, Blocks.WATER);
    private final ClientLevel world;
    private final BlockPos origin;
    private final int radius;
    private final Set<BlockPos> examined = new HashSet<>();
    private int window = 128;
    private boolean complete, closed;
    private String poolStatus = "not_surveyed";
    private final Map<String, Object> sourceObservations = new LinkedHashMap<>();

    PortalCastingSurvey(ClientLevel world, BlockPos origin, int radius) {
        this.world = world; this.origin = origin.immutable(); this.radius = radius;
        TargetIndex.register(world, FLUIDS);
    }

    NetherPortalCastingLayout tick() {
        if (complete) return null;
        poolStatus = "scanning";
        var query = TargetIndex.query(world, origin, List.of(Blocks.LAVA), window,
                (radius + 15) / 16, 8, Set.of(), true);
        int budget = 8;
        for (BlockPos seed : query.hits()) {
            if (seed.distSqr(origin) > (double) radius * radius || examined.contains(seed)) continue;
            if (budget-- == 0) return null;
            examined.add(seed);
            for (Direction shore : Direction.Plane.HORIZONTAL) {
                var candidate = new NetherPortalCastingLayout(seed, shore);
                if (atShore(world, candidate)) { poolStatus = "observed"; return candidate; }
            }
        }
        if (!query.complete()) return null;
        // 原生索引窗口满了就继续展开，不能把最近一批地下源格当成整个搜索范围已无池岸。
        if (query.hits().size() >= window && window < Integer.MAX_VALUE / 2) window *= 2;
        else { complete = true; poolStatus = "not_observed_in_loaded_scope"; }
        return null;
    }

    static boolean atShore(ClientLevel world, NetherPortalCastingLayout layout) {
        for (int x = -1; x <= 2; x++) {
            var lava = PortalPreparationSite.read(world, layout.cell(x, 0, 0));
            var bank = PortalPreparationSite.read(world, layout.cell(x, 0, 1));
            if (lava == null || !lava.is(Blocks.LAVA) || !lava.getFluidState().isSource()
                    || bank == null || !bank.getFluidState().isEmpty()
                    || !bank.isFaceSturdy(world, layout.cell(x, 0, 1), Direction.UP)) return false;
        }
        for (BlockPos at : layout.footprint()) {
            var state = PortalPreparationSite.read(world, at);
            if (state == null || state.hasBlockEntity() || state.getDestroySpeed(world, at) < 0
                    || NavigationSafetyContext.protectsMutation(at) || NavigationSafetyContext.forbidsBody(at)) return false;
        }
        return true;
    }

    /** 每桶取材前刷新源格，排除门框与模具；流水或已经凝固的旧命中都不能再次取桶。 */
    Source source(Block fluid, BlockPos center, Set<BlockPos> excluded) {
        var query = TargetIndex.query(world, center, List.of(fluid), 128, (radius + 15) / 16, 8,
                excluded, true);
        var position = query.hits().stream().filter(p -> p.distSqr(origin) <= (double) radius * radius)
                .filter(p -> !excluded.contains(p) && !NavigationSafetyContext.protectsMutation(p))
                .filter(p -> world.isLoaded(p) && world.getBlockState(p).is(fluid)
                        && world.getFluidState(p).isSource()).findFirst().orElse(null);
        // 记录的是本次局部源格查询；未扫描与扫描后没找到必须分开，不能把附近没有说成整个世界没有。
        var facts = new LinkedHashMap<String, Object>();
        facts.put("status", position != null ? "observed" : query.complete() ? "not_observed_in_loaded_scope" : "scanning");
        facts.put("scan_complete", query.complete()); facts.put("observed_game_time", world.getGameTime());
        if (position != null) facts.put("observed_source", NetherPortalCastingLayout.position(position));
        sourceObservations.put(BuiltInRegistries.BLOCK.getKey(fluid).toString(), facts);
        return new Source(position, query.complete(), query.hits().size());
    }

    /** 把已经完成的资源调查随缺口交付，让模型选择探索新区域，而不是重复扫描同一片已查过的地形。 */
    Map<String, Object> observations() {
        return Map.of("origin", NetherPortalCastingLayout.position(origin), "radius", radius, "loaded_only", true,
                "lava_pool_status", poolStatus, "source_lookups", Map.copyOf(sourceObservations));
    }

    boolean complete() { return complete; }
    @Override public void close() {
        if (!closed) { closed = true; TargetIndex.unregister(world, FLUIDS); }
    }
}
