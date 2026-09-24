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
    /** 分支只复制发生变化的物品账，不为每条配方复制整个 AE 网络。 */
    private static final class Pool {
        final Map<ResourceLocation, Long> stock, changed;
        Pool(Map<ResourceLocation, Long> stock) { this.stock = stock; changed = new HashMap<>(); }
        Pool(Pool other) { stock = other.stock; changed = new HashMap<>(other.changed); }
        long get(ResourceLocation item) { return Math.max(0, changed.getOrDefault(item, stock.getOrDefault(item, 0L))); }
        void put(ResourceLocation item, long count) { changed.put(item, count); }
        void add(ResourceLocation item, long count) { put(item, Math.addExact(get(item), count)); }
        Map<ResourceLocation, Long> remaining() { var all = new HashMap<>(stock); all.putAll(changed); return all; }
    }
    private static final class State {
        final Pool pool;
        final List<Need> supplies, crafts;
        long cost;
        State(Map<ResourceLocation, Long> stock) { pool = new Pool(stock); supplies = new ArrayList<>(); crafts = new ArrayList<>(); }
        State(State source) {
            pool = new Pool(source.pool); supplies = new ArrayList<>(source.supplies);
            crafts = new ArrayList<>(source.crafts); cost = source.cost;
        }
    }
    private record Allocation(State state, int missing) {}
    private record StateKey(Map<ResourceLocation, Long> changed, List<Need> supplies) {}
    private static final Comparator<State> ORDER = Comparator.comparingLong((State state) -> state.cost)
            .thenComparingInt(state -> state.supplies.size()).thenComparingInt(state -> state.crafts.size());
    private RecipeMaterialPlan() {}

    public static Result estimate(List<Need> needs, Map<ResourceLocation, Long> stock,
                                  Function<ResourceLocation, List<Recipe>> recipes,
                                  Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked) {
        Budget budget = new Budget();
        State initial = new State(stock);
        State planned = expand(needs, initial, recipes, sources, blocked, Set.of(), 32, budget).stream().min(ORDER).orElse(null);
        return planned == null ? new Result(false, !budget.exhausted, UNREACHABLE, List.of(), List.of(), stock)
                : new Result(true, !budget.exhausted, planned.cost, merge(planned.supplies), planned.crafts, planned.pool.remaining());
    }

    private static List<State> expand(List<Need> needs, State initial, Function<ResourceLocation, List<Recipe>> recipes,
                                      Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked,
                                      Set<ResourceLocation> visiting, int depth, Budget budget) {
        if (!budget.spend()) return List.of();
        List<State> states = List.of(initial);
        // 窄替代组先安排；同价分支保留各自库存账，避免先吃掉另一支唯一能用的材料而误报缺料。
        for (Need need : needs.stream().sorted(Comparator.comparingInt(row -> row.alternatives().size())).toList()) {
            List<State> candidates = new ArrayList<>();
            for (State state : states) for (Allocation allocation : allocate(need, state, blocked)) {
                State base = allocation.state(); int deficit = allocation.missing();
                if (deficit == 0) { candidates.add(base); continue; }
                for (ResourceLocation item : need.alternatives()) {
                    if (blocked.contains(item)) continue;
                    List<Recipe> choices = recipes.apply(item); Source source = sources.apply(item);
                    // 已知获取方式可在中间层切入；无配方边界只列为高成本未知需求，不宣称它是免费原料。
                    if (source.known() || choices.isEmpty()) {
                        State direct = new State(base); direct.supplies.add(new Need(List.of(item), deficit));
                        direct.cost = Math.min(UNREACHABLE, direct.cost + (long) deficit * source.unitCost());
                        candidates.add(direct);
                    }
                    if (visiting.contains(item)) continue;
                    if (depth <= 0) { budget.exhausted = true; continue; }
                    Set<ResourceLocation> path = new HashSet<>(visiting); path.add(item);
                    for (Recipe recipe : choices) {
                        if (!budget.spend()) break;
                        int batches = (int) (((long) deficit + recipe.outputCount() - 1) / recipe.outputCount());
                        List<Need> inputs = new ArrayList<>(); boolean bounded = true;
                        for (Need input : recipe.ingredients()) {
                            long count = (long) input.count() * batches;
                            if (count > 32768) { bounded = false; budget.exhausted = true; break; }
                            inputs.add(new Need(input.alternatives(), (int) count));
                        }
                        if (!bounded) continue;
                        for (State crafted : expand(inputs, new State(base), recipes, sources, blocked, path, depth - 1, budget)) {
                            crafted.pool.add(item, (long) batches * recipe.outputCount() - deficit);
                            crafted.crafts.add(new Need(List.of(item), deficit));
                            crafted.cost = Math.min(UNREACHABLE, crafted.cost + (long) batches * recipe.batchCost());
                            candidates.add(crafted);
                        }
                    }
                }
            }
            states = prune(candidates, budget);
            if (states.isEmpty()) break;
        }
        return states;
    }

    private static List<Allocation> allocate(Need need, State state, Set<ResourceLocation> blocked) {
        List<ResourceLocation> available = need.alternatives().stream()
                .filter(item -> !blocked.contains(item) && state.pool.get(item) > 0).toList();
        if (available.isEmpty()) return List.of(new Allocation(new State(state), need.count()));
        List<Allocation> allocations = new ArrayList<>(); Set<Map<ResourceLocation, Long>> seen = new HashSet<>();
        // 替代材料分别优先试一次；例如铁和金都能做配件时，也保留把铁留给另一个固定配方的选择。
        for (ResourceLocation first : available) {
            List<ResourceLocation> order = new ArrayList<>(); order.add(first);
            available.stream().filter(item -> !item.equals(first)).forEach(order::add);
            State trial = new State(state); int missing = need.count();
            for (ResourceLocation item : order) {
                long amount = trial.pool.get(item); int use = (int) Math.min(missing, amount);
                trial.pool.put(item, amount - use); missing -= use;
                if (missing == 0) break;
            }
            if (seen.add(Map.copyOf(trial.pool.changed))) allocations.add(new Allocation(trial, missing));
        }
        return allocations;
    }

    private static List<State> prune(List<State> candidates, Budget budget) {
        Map<StateKey, State> distinct = new LinkedHashMap<>();
        for (State candidate : candidates.stream().sorted(ORDER).toList())
            distinct.putIfAbsent(new StateKey(Map.copyOf(candidate.pool.changed), List.copyOf(candidate.supplies)), candidate);
        // 极宽的模组配方树保留有限候选并明确标为未穷尽；留下的每条路线仍各有完整数量账。
        if (distinct.size() > 24) budget.exhausted = true;
        return distinct.values().stream().limit(24).toList();
    }

    private static List<Need> merge(List<Need> needs) {
        // 汇总的是同一套选中配方的缺口，不把不同候选的半套材料拼成貌似可行的备料单。
        Map<List<ResourceLocation>, Integer> counts = new LinkedHashMap<>();
        needs.forEach(need -> counts.merge(need.alternatives(), need.count(), Math::addExact));
        List<Need> result = new ArrayList<>();
        counts.forEach((items, count) -> {
            // 大备料单按取物批次保留全部数量，不因展示模型的单项上限截掉后半份需求。
            for (int remaining = count; remaining > 0; remaining -= Math.min(remaining, 32768))
                result.add(new Need(items, Math.min(remaining, 32768)));
        });
        return List.copyOf(result);
    }
}
