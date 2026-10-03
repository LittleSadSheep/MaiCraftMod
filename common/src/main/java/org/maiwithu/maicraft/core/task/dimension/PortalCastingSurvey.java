// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/** 只在已加载范围寻找可取的源格和池岸；没有候选时返回观察事实，不凭空假定地下有岩浆。 */
final class PortalCastingSurvey implements AutoCloseable {
    record Source(BlockPos position, boolean complete, int observed) {}

    /** 池岸选址判据的逐项结果：每条拒绝原因都对应 {@link #atShoreDetailed} 中确实存在的分支。 */
    record ShoreCheck(boolean accepted, Reason reason, BlockPos at) {
        enum Reason {
            /** 池岸四格里发现了不是静源岩浆的格子，具体格子由 at 指向首个不符格。 */
            POOL_NOT_LAVA_SOURCE,
            /** 池岸行任一格未加载。 */
            POOL_UNLOADED,
            /** 脚印或施工空间任一格未加载。 */
            FOOTPRINT_UNLOADED,
            /** 脚印或施工空间含带方块实体的方块。 */
            FOOTPRINT_BLOCK_ENTITY,
            /** 脚印或施工空间含不可破坏方块。 */
            FOOTPRINT_UNBREAKABLE,
            /** 脚印或施工空间位于导航保护名单内。 */
            FOOTPRINT_PROTECTED,
            /** 脚印或施工空间禁止身体进入。 */
            FOOTPRINT_FORBIDDEN_BODY
        }

        static ShoreCheck ok() { return new ShoreCheck(true, null, null); }

        static ShoreCheck reject(Reason reason, BlockPos at) { return new ShoreCheck(false, reason, at); }
    }

    /** 一个合规池岸候选的全部观察事实；reachable 来自几何探针，只是建议序，真实导航才是裁决。 */
    record Candidate(NetherPortalCastingLayout layout, PortalCastingTerrain.Preparation preparation,
                     double distance, boolean reachable, ShoreCheck.Reason reachableReason,
                     BlockPos reachableAt) {}

    /** 一桶取材前刷新源格，排除门框与模具；流水或已经凝固的旧命中都不能再次取桶。 */
    private static final List<Block> FLUIDS = List.of(Blocks.LAVA, Blocks.WATER);
    /** 单次选址最多收容的候选数上限；超过即停止扩张，避免远处不可达池把 deadline 烧光。 */
    private static final int MAX_CANDIDATES = 128;
    private final ClientLevel world;
    private final BlockPos origin;
    private final int radius;
    private final Set<BlockPos> examined = new HashSet<>();
    private int window = 128;
    private boolean complete, closed;
    private String poolStatus = "not_surveyed";
    private final Map<String, Object> sourceObservations = new LinkedHashMap<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private Candidate selected;
    private int tried;
    private String candidatesStatus = "not_evaluated";
    private final Map<ShoreCheck.Reason, Integer> rejectionTally = new LinkedHashMap<>();
    private PortalCastingTerrain.Preparation terrain;

    PortalCastingSurvey(ClientLevel world, BlockPos origin, int radius) {
        this.world = world; this.origin = origin.immutable(); this.radius = radius;
        TargetIndex.register(world, FLUIDS);
    }

    NetherPortalCastingLayout tick() {
        if (complete) return null;
        if (selected != null) return selected.layout();
        poolStatus = "scanning";
        var query = TargetIndex.query(world, origin, List.of(Blocks.LAVA), window,
                (radius + 15) / 16, 8, Set.of(), true);
        // 按离 origin 的平方距离升序遍历种子；远处的候选最后才考虑。
        var ordered = query.hits().stream()
                .filter(p -> p.distSqr(origin) <= (double) radius * radius)
                .filter(p -> !examined.contains(p))
                .sorted(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .toList();
        int budget = 8;
        for (BlockPos seed : ordered) {
            if (budget-- == 0 || candidates.size() >= MAX_CANDIDATES) break;
            examined.add(seed);
            for (Direction shore : Direction.Plane.HORIZONTAL) {
                var candidateLayout = new NetherPortalCastingLayout(seed, shore);
                var check = atShoreDetailed(world, candidateLayout);
                if (!check.accepted()) {
                    rejectionTally.merge(check.reason(), 1, Integer::sum);
                    continue;
                }
                var proposed = PortalCastingTerrain.inspect(world, candidateLayout, origin, radius);
                if (!proposed.reserveObserved()) continue;
                var stand = reachableAt(world, candidateLayout);
                var candidate = new Candidate(candidateLayout, proposed,
                        Math.sqrt(seed.distSqr(origin)), stand.accepted(),
                        stand.reason(), stand.at());
                // 种子按距离升序处理，首个可达候选就是最近的可达池，立即锁定——
                // 绝不为"可能还有更近的"拖延选定，否则预算耗尽会让任务永远停在扫描态。
                candidates.add(candidate);
                if (stand.accepted()) {
                    selected = candidate; terrain = proposed;
                    poolStatus = "observed";
                    candidatesStatus = "selected_reachable";
                    return selected.layout();
                }
            }
        }
        // 预算耗尽且仍有未检种子：保持扫描态，下一刻从游标继续；种子扫完才进入下面的结算。
        if (!ordered.isEmpty() && budget <= 0) return null;
        if (candidates.isEmpty()) {
            if (!query.complete()) return null;
            // 原生索引窗口满了就继续展开，不能把最近一批地下源格当成整个搜索范围已无池岸。
            if (query.hits().size() >= window && window < Integer.MAX_VALUE / 2) {
                window *= 2;
                return null;
            }
            complete = true;
            poolStatus = "not_observed_in_loaded_scope";
            // 查询只收静源岩浆：检过种子就是观察到源岩浆，一个都没检到才是真正的零源。
            candidatesStatus = examined.isEmpty()
                    ? "zero_source_lava_observed"
                    : "source_lava_but_no_compliant_pool";
            return null;
        }
        // 候选全部预检不可达：选最近的先行 PREPARE_SITE，真实导航是最终裁决，探针结论不是。
        candidates.sort(Comparator.comparingDouble(c -> c.distance));
        selected = candidates.get(0); terrain = selected.preparation();
        poolStatus = "observed";
        candidatesStatus = "all_unreachable_at_selection";
        return selected.layout();
    }

    /** 已选候选失败且尚未提交任何施工方块时，按距离取其后的下一个候选；全部用尽返回 null。 */
    Candidate nextFallback(boolean nothingPlaced) {
        if (!nothingPlaced) return null;
        tried++;
        // 预检结论只是建议序：被探针否决的候选同样参与回退，真实导航失败才算数。
        // 只向后推进（按引用定位当前项）：从头找"第一个不等于当前项的"会在前两个候选间来回振荡。
        candidates.sort(Comparator.comparingDouble(c -> c.distance));
        int from = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == selected) { from = i; break; }
        }
        for (int i = from + 1; i < candidates.size(); i++) {
            selected = candidates.get(i); terrain = selected.preparation();
            poolStatus = "observed";
            candidatesStatus = "fallback_selected";
            return selected;
        }
        return null;
    }

    /** 单次选址中各类拒绝原因的累计计数；空 map 表示无拒绝。 */
    Map<ShoreCheck.Reason, Integer> rejectionTally() { return Map.copyOf(rejectionTally); }

    /** 已收容候选的快照，含已选与未选；供回执统计与测试断言。 */
    List<Candidate> candidates() { return List.copyOf(candidates); }

    static boolean atShore(ClientLevel world, NetherPortalCastingLayout layout) {
        return atShoreDetailed(world, layout).accepted();
    }

    /** 逐项检查起手四格与脚印格，每条拒绝单独归类；未加载格保持原行为（视为拒绝）。 */
    static ShoreCheck atShoreDetailed(ClientLevel world, NetherPortalCastingLayout layout) {
        for (int x = -1; x <= 2; x++) {
            BlockPos at = layout.cell(x, 0, 0);
            BlockState lava = PortalPreparationSite.read(world, at);
            if (lava == null) return ShoreCheck.reject(ShoreCheck.Reason.POOL_UNLOADED, at);
            if (!lava.is(Blocks.LAVA) || !lava.getFluidState().isSource())
                return ShoreCheck.reject(ShoreCheck.Reason.POOL_NOT_LAVA_SOURCE, at);
        }
        for (BlockPos at : layout.footprint()) {
            BlockState state = PortalPreparationSite.read(world, at);
            if (state == null) return ShoreCheck.reject(ShoreCheck.Reason.FOOTPRINT_UNLOADED, at);
            if (state.hasBlockEntity()) return ShoreCheck.reject(ShoreCheck.Reason.FOOTPRINT_BLOCK_ENTITY, at);
            if (state.getDestroySpeed(world, at) < 0) return ShoreCheck.reject(ShoreCheck.Reason.FOOTPRINT_UNBREAKABLE, at);
            if (NavigationSafetyContext.protectsMutation(at)) return ShoreCheck.reject(ShoreCheck.Reason.FOOTPRINT_PROTECTED, at);
            if (NavigationSafetyContext.forbidsBody(at)) return ShoreCheck.reject(ShoreCheck.Reason.FOOTPRINT_FORBIDDEN_BODY, at);
        }
        return ShoreCheck.ok();
    }

    /** 站台任一格能让角色干燥站直（脚头无液体、可通行、下方有支撑）即视为可达；未加载格视为不可站。 */
    static ShoreCheck reachableAt(ClientLevel world, NetherPortalCastingLayout layout) {
        for (BlockPos floor : PortalCastingTerrain.platform(layout)) {
            BlockPos feet = floor.above();
            // 脚位、头位与支撑面三格都必须已加载；未加载不能读方块，也不构成不可达的证据。
            if (!world.isLoaded(floor) || !world.isLoaded(feet) || !world.isLoaded(feet.above())) continue;
            if (BlockHelper.isDryStandable(world, feet)) return ShoreCheck.ok();
        }
        var platform = PortalCastingTerrain.platform(layout);
        return ShoreCheck.reject(ShoreCheck.Reason.FOOTPRINT_FORBIDDEN_BODY,
                platform.isEmpty() ? layout.origin() : platform.get(platform.size() / 2));
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
        var facts = new LinkedHashMap<String, Object>();
        facts.put("origin", NetherPortalCastingLayout.position(origin)); facts.put("radius", radius); facts.put("loaded_only", true);
        facts.put("lava_pool_status", poolStatus);
        // B4 的三种状态装在独立字段里，不重定义 lava_pool_status 既有枚举的语义：
        // 查询只收静源岩浆，检过种子即观察到源岩浆；零候选加零种子才是真正的零源。
        facts.put("source_lava_observation", examined.isEmpty() ? "not_observed" : "observed");
        facts.put("candidates_status", candidatesStatus);
        facts.put("candidates_total", candidates.size());
        facts.put("candidates_tried", tried);
        facts.put("candidates_reachable_count", candidates.stream().filter(Candidate::reachable).count());
        facts.put("source_lookups", Map.copyOf(sourceObservations));
        if (!rejectionTally.isEmpty()) {
            var byReason = new LinkedHashMap<String, Integer>();
            rejectionTally.forEach((r, n) -> byReason.put(r.name(), n));
            facts.put("rejection_tally", byReason);
        }
        if (terrain != null) facts.put("site_preparation", terrain.facts());
        return facts;
    }

    boolean complete() { return complete; }
    @Override public void close() {
        if (!closed) { closed = true; TargetIndex.unregister(world, FLUIDS); }
    }
}
