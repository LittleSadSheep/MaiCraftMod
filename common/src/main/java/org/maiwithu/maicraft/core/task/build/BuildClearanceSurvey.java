package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;

/** 只读检查清障冲突，再按水平移动距离核对整份蓝图的新位置；建议不会移动角色或改写冻结工程。 */
public final class BuildClearanceSurvey {
    public static final String FAILURE = "build_clearance_not_whitelisted";
    private static final int RADIUS = 16, MAX_SEARCH_READS = 262144;
    private static final List<BlockPos> SHIFTS = shifts();
    // 未加载返回 null；世界边界和保护格用 mutable 拒绝，已有符合蓝图的方块仍可保留。
    record Observation(BlockState state, boolean mutable, boolean breakable) {}
    private final List<BuildTaskRecord.Target> targets;
    private final ReplaceMode replace;
    private final boolean replaceEntities;
    private final String dimension;
    private final Function<BlockPos, Observation> observe;
    private final BiPredicate<BlockPos, BlockState> ownedSupport;
    private final List<Map<String, Object>> obstacles = new ArrayList<>(), suggestions = new ArrayList<>();
    private int scanned, conflicts, unloaded, candidate, candidateCell, searchReads, unknownCandidates;
    private int bestDistance = Integer.MAX_VALUE;
    private boolean done, limited;

    public static BuildClearanceSurvey forPlan(LocalPlayer player, BuildTaskRecord plan) {
        return forPlan(player, plan, NavigationSafetyContext.protectedMutationCells());
    }

    public static BuildClearanceSurvey forPlan(LocalPlayer player, BuildTaskRecord plan, LongSet inheritedProtection) {
        var level = player.level();
        // 候选新址也避开任务继承的保护区和机器其他层，不能因跨刻离开原作用域就丢失这些限制。
        var protectedArea = new LongOpenHashSet(inheritedProtection);
        protectedArea.addAll(NavigationSafetyContext.protectedMutationCells());
        plan.protectedNavigationCells().forEach(at -> protectedArea.add(at.asLong()));
        plan.materialSupplyProtection().forEach(at -> protectedArea.add(at.asLong()));
        var forbidden = NavigationSafetyContext.forbiddenBodyCells();
        return new BuildClearanceSurvey(plan.targets, plan.replaceMode, plan.replaceBlockEntities,
                level.dimension().location().toString(), at -> {
                    if (level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at))
                        return new Observation(null, false, false);
                    if (!level.isLoaded(at)) return null;
                    var state = level.getBlockState(at);
                    return new Observation(state, !protectedArea.contains(at.asLong()) && !forbidden.contains(at.asLong()),
                            state.getDestroySpeed(level, at) >= 0 && state.getFluidState().isEmpty());
                }, plan.scaffoldLedger()::owns);
    }

    BuildClearanceSurvey(List<BuildTaskRecord.Target> targets, ReplaceMode replace, boolean replaceEntities,
                         String dimension, Function<BlockPos, Observation> observe,
                         BiPredicate<BlockPos, BlockState> ownedSupport) {
        this.targets = List.copyOf(targets); this.replace = replace; this.replaceEntities = replaceEntities;
        this.dimension = dimension; this.observe = observe; this.ownedSupport = ownedSupport;
    }

    /** 每刻只读取有限格子；先收集原地冲突，再从最近偏移开始逐格检查，未知区块绝不当作空地。 */
    public boolean advance(int budget) {
        while (!done && budget-- > 0) {
            if (scanned < targets.size()) {
                int index = scanned++;
                var target = targets.get(index);
                var seen = observe.apply(target.pos());
                if (seen == null) { unloaded++; continue; }
                if (seen.state() != null && needsClearance(target, seen.state())
                        && !ownedSupport.test(target.pos(), seen.state()) && !ClearanceWhitelist.allows(seen.state())) {
                    conflicts++;
                    if (obstacles.size() < 16) obstacles.add(Map.of("at", coordinates(target.pos()),
                            "block_id", id(seen.state()), "target_index", index,
                            "expected_block_id", id(target.desiredState())));
                }
                continue;
            }
            if (conflicts == 0 || candidate == SHIFTS.size() || suggestions.size() == 3
                    || distance(SHIFTS.get(candidate)) > bestDistance) { done = true; continue; }
            if (searchReads >= MAX_SEARCH_READS) { limited = true; done = true; continue; }
            BlockPos shift = SHIFTS.get(candidate);
            var target = targets.get(candidateCell);
            var seen = observe.apply(target.pos().offset(shift)); searchReads++;
            if (seen == null) { unknownCandidates++; nextCandidate(); continue; }
            if (!usable(target, seen)) { nextCandidate(); continue; }
            if (++candidateCell == targets.size()) {
                bestDistance = distance(shift);
                suggestions.add(Map.of("offset", coordinates(shift), "horizontal_distance_squared", bestDistance,
                        "checked_targets", targets.size(), "clearance_check", "passed"));
                nextCandidate();
            }
        }
        return done;
    }

    // 每个候选必须覆盖整份蓝图，包括空气目标；不会只避开第一个障碍就把其他房间移到机器或未知区块上。
    private boolean usable(BuildTaskRecord.Target target, Observation seen) {
        if (seen.state() == null) return false;
        if (target.constructionMatches(seen.state())) return true;
        if (!seen.mutable()) return false;
        if (seen.state().isAir()) return true;
        return seen.breakable() && ClearanceWhitelist.allows(seen.state())
                && replace.allows(seen.state(), target.desiredState())
                && (replaceEntities || !seen.state().hasBlockEntity());
    }

    static boolean needsClearance(BuildTaskRecord.Target target, BlockState live) {
        return !live.isAir() && !target.constructionMatches(live);
    }
    private void nextCandidate() { candidate++; candidateCell = 0; }
    public boolean blocked() { return conflicts > 0; }

    /** 位置和偏移使用有明确含义的数组，供 LLM 选择新址；不把局部核对结果宣称为完整施工可行性。 */
    public Map<String, Object> report() {
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", dimension); result.put("whitelist_config", ClearanceWhitelist.CONFIG);
        result.put("conflict_count", conflicts); result.put("obstacles", List.copyOf(obstacles));
        result.put("obstacles_truncated", conflicts > obstacles.size()); result.put("unloaded_targets", unloaded);
        result.put("suggested_offsets", List.copyOf(suggestions));
        result.put("relocation_scope", "entire_blueprint_new_project_same_elevation");
        result.put("search", Map.of("horizontal_radius", RADIUS, "checked_observations", searchReads,
                "unknown_candidates", unknownCandidates, "budget_exhausted", limited,
                "minimum_proven_in_radius", done && !limited && unknownCandidates == 0 && !suggestions.isEmpty()));
        result.put("advice", suggestions.isEmpty()
                ? "附近已加载范围内尚未证明可用的平移位置；扩大场地观察后考虑其他选址，保留名单外障碍。"
                : "优先考虑建议中的最小水平偏移，保持蓝图形状和高度；重新观察地基与通路后在新址创建工程。旧工程及已建部分不会自动搬迁。"
        );
        result.put("requires_site_review", true);
        return Map.copyOf(result);
    }

    private static List<BlockPos> shifts() {
        var result = new ArrayList<BlockPos>();
        for (int x = -RADIUS; x <= RADIUS; x++) for (int z = -RADIUS; z <= RADIUS; z++)
            if ((x != 0 || z != 0) && x * x + z * z <= RADIUS * RADIUS) result.add(new BlockPos(x, 0, z));
        result.sort(Comparator.comparingInt(BuildClearanceSurvey::distance)
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(result);
    }
    private static int distance(BlockPos shift) { return shift.getX() * shift.getX() + shift.getZ() * shift.getZ(); }
    private static List<Integer> coordinates(BlockPos pos) { return List.of(pos.getX(), pos.getY(), pos.getZ()); }
    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
}
