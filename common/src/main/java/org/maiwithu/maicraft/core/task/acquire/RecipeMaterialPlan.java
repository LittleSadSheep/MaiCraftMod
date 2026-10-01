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
                         List<Need> crafts, Map<ResourceLocation, Long> remaining, Set<ResourceLocation> preferredMaterialsUsed) {
        public Result {
            supplies = List.copyOf(supplies); crafts = List.copyOf(crafts); remaining = Map.copyOf(remaining);
            preferredMaterialsUsed = Set.copyOf(preferredMaterialsUsed);
        }
        public Result(boolean feasible, boolean searchComplete, long cost, List<Need> supplies,
                List<Need> crafts, Map<ResourceLocation, Long> remaining) {
            this(feasible, searchComplete, cost, supplies, crafts, remaining, Set.of());
        }
    }
    // 偏好只比较真实候选树用到的材料；数量、库存与配方循环仍由同一本材料账核对。
    public static final Comparator<Result> ORDER = Comparator
            .comparing((Result result) -> !result.feasible())
            .thenComparing(result -> !result.supplies().isEmpty())
            .thenComparingInt(result -> -result.preferredMaterialsUsed().size())
            .thenComparingLong(Result::cost);
    private static final class Budget {
        int remaining;
        boolean exhausted;
        // 每轮只花分配到的展开次数；浅层模组配方再宽也要给下一层普通合成留出比较机会。
        Budget(int limit) { remaining = limit; }
        boolean spend() {
            if (remaining == 0) { exhausted = true; return false; }
            remaining--;
            return true;
        }
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
        final Set<ResourceLocation> preferredUsed = new HashSet<>();
        long cost;
        State(Map<ResourceLocation, Long> stock) { pool = new Pool(stock); supplies = new ArrayList<>(); crafts = new ArrayList<>(); }
        State(State source) {
            pool = new Pool(source.pool); supplies = new ArrayList<>(source.supplies);
            crafts = new ArrayList<>(source.crafts); cost = source.cost;
            preferredUsed.addAll(source.preferredUsed);
        }
    }
    private record Allocation(State state, int missing) {}
    private record StateKey(Map<ResourceLocation, Long> changed, List<Need> supplies, Set<ResourceLocation> preferredUsed) {}
    private static final Comparator<State> STATE_ORDER = Comparator
            .comparing((State state) -> !state.supplies.isEmpty())
            .thenComparingInt(state -> -state.preferredUsed.size()).thenComparingLong(state -> state.cost)
            .thenComparingInt(state -> state.supplies.size()).thenComparingInt(state -> state.crafts.size());
    private RecipeMaterialPlan() {}

    public static Result estimate(List<Need> needs, Map<ResourceLocation, Long> stock,
                                  Function<ResourceLocation, List<Recipe>> recipes,
                                  Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked) {
        return estimate(needs, stock, recipes, sources, blocked, Set.of());
    }

    /** 已耗尽的获取入口只阻止继续补料；后来真正观察到的现货仍可直接用于原配方。 */
    public static Result estimate(List<Need> needs, Map<ResourceLocation, Long> stock,
                                  Function<ResourceLocation, List<Recipe>> recipes,
                                  Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked,
                                  Set<ResourceLocation> unavailable) {
        return estimate(needs, stock, recipes, sources, blocked, unavailable, Set.of());
    }

    /** LLM 的材料倾向沿整条依赖链传递；偏好路线不可展开时仍保留其他完整候选。 */
    public static Result estimate(List<Need> needs, Map<ResourceLocation, Long> stock,
                                  Function<ResourceLocation, List<Recipe>> recipes,
                                  Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked,
                                  Set<ResourceLocation> unavailable, Set<ResourceLocation> preferred) {
        int remainingBudget = 8192;
        boolean searchComplete = false;
        // 排序与展开复用同一次只读元数据，避免比较宽标签时反复查询相同材料的来源与配方。
        Map<ResourceLocation, List<Recipe>> recipeCache = new HashMap<>();
        Map<ResourceLocation, Source> sourceCache = new HashMap<>();
        Function<ResourceLocation, List<Recipe>> recipeLookup = item -> recipeCache.computeIfAbsent(item, recipes);
        Function<ResourceLocation, Source> sourceLookup = item -> sourceCache.computeIfAbsent(item, sources);
        // 先找短而完整的备料路线，再增加合成层数；羊毛互染等循环不能先吃光预算，把普通原料路线挤掉。
        // 总预算不变，但为后续深度保留份额；否则桶和木板的大量替代配方会在第一轮耗尽预算，只留下未知材料。
        State planned = null;
        for (int depth = 1, round = 0; depth <= 32 && remainingBudget > 0; depth *= 2, round++) {
            int allowance = remainingBudget / (6 - round);
            Budget budget = new Budget(allowance);
            State candidate = expand(needs, new State(stock), recipeLookup, sourceLookup, blocked, unavailable,
                    Set.of(), depth, budget, preferred).stream().min(STATE_ORDER).orElse(null);
            remainingBudget -= allowance - budget.remaining;
            if (candidate != null && (planned == null || STATE_ORDER.compare(candidate, planned) < 0)) planned = candidate;
            // 没有深度截断、候选裁剪或预算耗尽时才算搜索完整；否则在剩余预算内继续比较更深路线。
            searchComplete = !budget.exhausted;
            if (searchComplete) break;
        }
        return planned == null ? new Result(false, searchComplete, UNREACHABLE, List.of(), List.of(), stock)
                : new Result(true, searchComplete, planned.cost, merge(planned.supplies), planned.crafts,
                        planned.pool.remaining(), planned.preferredUsed);
    }

    private static List<State> expand(List<Need> needs, State initial, Function<ResourceLocation, List<Recipe>> recipes,
                                      Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked,
                                      Set<ResourceLocation> unavailable, Set<ResourceLocation> visiting, int depth, Budget budget,
                                      Set<ResourceLocation> preferred) {
        if (!budget.spend()) return List.of();
        List<State> states = List.of(initial);
        // 木桶等配方的相同原料格先合并数量，再扣除可混用的现货；避免每个格子重复展开整套木种组合。
        // 窄替代组仍先安排，同价分支保留各自库存账，避免先吃掉另一支唯一能用的材料。
        for (Need need : merge(needs).stream().sorted(Comparator.comparingInt(row -> row.alternatives().size())).toList()) {
            List<State> candidates = new ArrayList<>();
            for (State state : states) for (Allocation allocation : allocate(need, state, blocked, preferred)) {
                State base = allocation.state(); int deficit = allocation.missing();
                if (deficit == 0) { candidates.add(base); continue; }
                // 固定预算内先考察现货或已知获取方式更近的路线，不能让前面的未知分支把普通木材路线挤出搜索。
                for (ResourceLocation item : need.alternatives().stream().sorted(Comparator
                        .comparingInt((ResourceLocation id) -> -preferenceHint(id, recipes, preferred))
                        .thenComparingDouble(id -> itemPriority(id, base.pool, recipes, sources, blocked, unavailable))).toList()) {
                    if (blocked.contains(item) || unavailable.contains(item)) continue;
                    List<Recipe> choices = recipes.apply(item); Source source = sources.apply(item);
                    // 已知获取方式可在中间层切入；无配方边界只列为高成本未知需求，不宣称它是免费原料。
                    if (source.known() || choices.isEmpty()) {
                        State direct = new State(base); direct.supplies.add(new Need(List.of(item), deficit));
                        if (preferred.contains(item)) direct.preferredUsed.add(item);
                        direct.cost = Math.min(UNREACHABLE, direct.cost + (long) deficit * source.unitCost());
                        candidates.add(direct);
                    }
                    if (visiting.contains(item)) continue;
                    if (depth <= 0) { budget.exhausted = true; continue; }
                    Set<ResourceLocation> path = new HashSet<>(visiting); path.add(item);
                    for (Recipe recipe : choices.stream().sorted(Comparator
                            .comparingInt((Recipe row) -> -preferredInputs(row, preferred))
                            .thenComparingDouble(row -> recipePriority(row, base.pool, sources, blocked, unavailable))).toList()) {
                        if (!budget.spend()) break;
                        int batches = (int) (((long) deficit + recipe.outputCount() - 1) / recipe.outputCount());
                        List<Need> inputs = new ArrayList<>(); boolean bounded = true;
                        for (Need input : recipe.ingredients()) {
                            long count = (long) input.count() * batches;
                            if (count > 32768) { bounded = false; budget.exhausted = true; break; }
                            inputs.add(new Need(input.alternatives(), (int) count));
                        }
                        if (!bounded) continue;
                        for (State crafted : expand(inputs, new State(base), recipes, sources, blocked, unavailable, path, depth - 1, budget, preferred)) {
                            crafted.pool.add(item, (long) batches * recipe.outputCount() - deficit);
                            crafted.crafts.add(new Need(List.of(item), deficit));
                            if (preferred.contains(item)) crafted.preferredUsed.add(item);
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

    private static int preferenceHint(ResourceLocation item, Function<ResourceLocation, List<Recipe>> recipes,
            Set<ResourceLocation> preferred) {
        // 先给偏好材料及其直接加工路线展开机会，避免宽标签在预算耗尽前把主人提示的木种淹没。
        if (preferred.isEmpty()) return 0;
        if (preferred.contains(item)) return 2;
        return recipes.apply(item).stream().anyMatch(recipe -> preferredInputs(recipe, preferred) > 0) ? 1 : 0;
    }

    private static int preferredInputs(Recipe recipe, Set<ResourceLocation> preferred) {
        return (int) recipe.ingredients().stream().flatMap(need -> need.alternatives().stream())
                .filter(preferred::contains).distinct().count();
    }

    private static double itemPriority(ResourceLocation item, Pool pool, Function<ResourceLocation, List<Recipe>> recipes,
                                       Function<ResourceLocation, Source> sources, Set<ResourceLocation> blocked,
                                       Set<ResourceLocation> unavailable) {
        if (blocked.contains(item) || unavailable.contains(item)) return Double.POSITIVE_INFINITY;
        var choices = recipes.apply(item); var source = sources.apply(item);
        double score = source.known() || choices.isEmpty() ? source.unitCost() : Double.POSITIVE_INFINITY;
        for (Recipe recipe : choices) score = Math.min(score, recipePriority(recipe, pool, sources, blocked, unavailable));
        return score;
    }

    private static double recipePriority(Recipe recipe, Pool pool, Function<ResourceLocation, Source> sources,
                                         Set<ResourceLocation> blocked, Set<ResourceLocation> unavailable) {
        // 只看下一层作为排序提示；共享库存、整批数量和循环仍由正式展开逐项扣账，不能凭这个分数宣称够料。
        double score = recipe.batchCost();
        for (Need input : recipe.ingredients()) {
            double cheapest = Double.POSITIVE_INFINITY;
            for (ResourceLocation item : input.alternatives()) {
                if (blocked.contains(item)) continue;
                long missing = Math.max(0L, input.count() - pool.get(item));
                if (missing == 0) cheapest = 0;
                else if (!unavailable.contains(item)) cheapest = Math.min(cheapest, missing * (double) sources.apply(item).unitCost());
            }
            score += cheapest;
        }
        return score / recipe.outputCount();
    }

    private static List<Allocation> allocate(Need need, State state, Set<ResourceLocation> blocked,
            Set<ResourceLocation> preferred) {
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
                if (use > 0 && preferred.contains(item)) trial.preferredUsed.add(item);
                if (missing == 0) break;
            }
            if (seen.add(Map.copyOf(trial.pool.changed))) allocations.add(new Allocation(trial, missing));
        }
        return allocations;
    }

    private static List<State> prune(List<State> candidates, Budget budget) {
        Map<StateKey, State> distinct = new LinkedHashMap<>();
        for (State candidate : candidates.stream().sorted(STATE_ORDER).toList())
            distinct.putIfAbsent(new StateKey(Map.copyOf(candidate.pool.changed), List.copyOf(candidate.supplies),
                    Set.copyOf(candidate.preferredUsed)), candidate);
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
