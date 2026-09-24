// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Need;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Recipe;

/** 先按层扣现货，再比较整条配方的补料切点；只产生材料账和合成顺序，不提前改变库存或世界。 */
public final class RecipeMaterialPlan {
    public static final long UNREACHABLE = 1_000_000_000L;
    public record Source(boolean known, int unitCost) {
        public Source { if (unitCost < 1) throw new IllegalArgumentException("material_source_cost"); }
    }
    public record Result(boolean feasible, boolean searchComplete, long cost, List<Need> supplies,
                         List<Need> crafts, Map<ResourceLocation, Long> remaining) {
        public Result { supplies = List.copyOf(supplies); crafts = List.copyOf(crafts); remaining = Map.copyOf(remaining); }
    }
    private static final class Budget {
        int remaining = 8192;
        boolean exhausted;
        boolean spend() { if (--remaining < 0) { exhausted = true; return false; } return true; }
    }
    private static final class State {
        final Map<ResourceLocation, Long> pool;
        final List<Need> supplies, crafts;
        long cost;
        State(Map<ResourceLocation, Long> stock) { pool = new HashMap<>(stock); supplies = new ArrayList<>(); crafts = new ArrayList<>(); }
        State(State source) {
            pool = new HashMap<>(source.pool); supplies = new ArrayList<>(source.supplies);
            crafts = new ArrayList<>(source.crafts); cost = source.cost;
        }
    }
    private RecipeMaterialPlan() {}

    public static Result estimate(List<Need> needs, Map<ResourceLocation, Long> stock,
                                  Function<ResourceLocation, List<Recipe>> recipes,
                                  Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked) {
        Budget budget = new Budget();
        State initial = new State(stock);
        State planned = expand(needs, initial, recipes, sources, blocked, Set.of(), 32, budget);
        return planned == null ? new Result(false, !budget.exhausted, UNREACHABLE, List.of(), List.of(), stock)
                : new Result(true, !budget.exhausted, planned.cost, merge(planned.supplies), planned.crafts, planned.pool);
    }

    private static State expand(List<Need> needs, State state, Function<ResourceLocation, List<Recipe>> recipes,
                                Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked,
                                Set<ResourceLocation> visiting, int depth, Budget budget) {
        if (!budget.spend()) return null;
        List<Need> missing = new ArrayList<>();
        // 同层的窄替代组先占用现货，再展开任何子树；不能先做配件而吃掉另一项已经够用的材料。
        for (Need need : needs.stream().sorted(Comparator.comparingInt(row -> row.alternatives().size())).toList()) {
            int deficit = need.count();
            for (ResourceLocation item : need.alternatives()) {
                if (blocked.contains(item)) continue;
                long available = Math.max(0, state.pool.getOrDefault(item, 0L));
                int used = (int) Math.min(deficit, available);
                state.pool.put(item, available - used); deficit -= used;
                if (deficit == 0) break;
            }
            if (deficit > 0) missing.add(new Need(need.alternatives(), deficit));
        }
        for (Need need : missing) {
            // 合成的整批余料可跨后续替代组混用；先扣全部可用余料，再比较需要新增的那部分。
            State reserved = new State(state);
            int shortage = need.count();
            for (ResourceLocation item : need.alternatives()) {
                if (blocked.contains(item)) continue;
                long available = Math.max(0, reserved.pool.getOrDefault(item, 0L));
                int used = (int) Math.min(shortage, available);
                reserved.pool.put(item, available - used); shortage -= used;
            }
            if (shortage == 0) { state = reserved; continue; }
            State best = null;
            for (ResourceLocation item : need.alternatives()) {
                if (blocked.contains(item)) continue;
                State base = new State(reserved);
                int deficit = shortage;
                List<Recipe> choices = recipes.apply(item);
                Source source = sources.apply(item);
                // 只有明确的直接来源可以在中间层切入；无配方的边界允许列为未知缺料，但绝不算零成本。
                if (source.known() || choices.isEmpty()) {
                    State direct = new State(base);
                    direct.supplies.add(new Need(List.of(item), deficit));
                    direct.cost = Math.min(UNREACHABLE, direct.cost + (long) deficit * source.unitCost());
                    best = better(best, direct);
                }
                if (visiting.contains(item)) continue;
                if (depth <= 0) { budget.exhausted = true; continue; }
                Set<ResourceLocation> path = new HashSet<>(visiting); path.add(item);
                for (Recipe recipe : choices) {
                    if (!budget.spend()) break;
                    int batches = (deficit + recipe.outputCount() - 1) / recipe.outputCount();
                    List<Need> inputs = new ArrayList<>();
                    boolean bounded = true;
                    for (Need input : recipe.ingredients()) {
                        long count = (long) input.count() * batches;
                        if (count > 32768) { bounded = false; budget.exhausted = true; break; }
                        inputs.add(new Need(input.alternatives(), (int) count));
                    }
                    if (!bounded) continue;
                    State crafted = expand(inputs, new State(base), recipes, sources, blocked, path, depth - 1, budget);
                    if (crafted == null) continue;
                    long remainder = (long) batches * recipe.outputCount() - deficit;
                    crafted.pool.merge(item, remainder, Long::sum);
                    crafted.crafts.add(new Need(List.of(item), deficit));
                    crafted.cost = Math.min(UNREACHABLE, crafted.cost + batches);
                    best = better(best, crafted);
                }
            }
            if (best == null) return null;
            state = best;
        }
        return state;
    }

    private static State better(State previous, State candidate) {
        if (previous == null || candidate.cost < previous.cost
                || candidate.cost == previous.cost && candidate.supplies.size() < previous.supplies.size()) return candidate;
        return previous;
    }

    private static List<Need> merge(List<Need> needs) {
        // 汇总的是同一套选中配方的缺口，不把不同候选的半套材料拼成貌似可行的备料单。
        Map<List<ResourceLocation>, Integer> counts = new LinkedHashMap<>();
        needs.forEach(need -> counts.merge(need.alternatives(), need.count(), Math::addExact));
        return counts.entrySet().stream().map(entry -> new Need(entry.getKey(), entry.getValue())).toList();
    }
}
