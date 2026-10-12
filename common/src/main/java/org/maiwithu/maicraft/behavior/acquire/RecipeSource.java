// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 做出来的来源：合成、烧炼、石切台同走一条路——配方从世界真实的配方管理器读（含模组配方），
 * 设施从世界记忆里记过的工作站里找（用过一台才会记得一台）。原料缺多少、烧炼要多少燃料，
 * 递归回到拿到物品的引擎去弄，用途标签写清是为谁备的，备完了去设施上动手做。
 *
 * <p>备料弄不全不白跑：某样原料弄不来，先把身上的料够做的件数做掉（要 16 根火把、木炭只够 8 根，就先做 8 根），
 * 引擎按拿到几件、还差几件报部分完成，弄不来的原因一并交代；身上的料连一份都不够才以备料的原因失败。
 * 某样原料弄不来、身上连一份的量都没有时当场放弃这个做法，不再去弄其余原料（没有羊毛就不去砍做床板的木头）。
 */
public final class RecipeSource implements ItemSource {

    /** 找工作站的半径：再远就不算顺手，宁可去采原料。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;
    /**
     * 记得的工作台比这远就不跑过去了：一张工作台只要四块木板，玩家会就地再摆一张，
     * 不会为做一把镐子走回几十格外的老地方（路上还要搭路、绕悬崖）。熔炉、石切台造价高，照旧走过去用。
     */
    public static final int HANDY_CRAFTING_TABLE_BLOCKS = 16;

    /** 缺得一样多的做法最多挨个试几种：木板十来种木头，再多也不一直试下去。 */
    private static final int MAX_VARIANTS = 12;

    /** 烧炼一件东西要烧多少刻：原版熔炉的固定速度。 */
    private static final int SMELT_TICKS_PER_ITEM = 200;
    /** 备燃料时的规划数字：一块煤烧 1600 刻，按它算要带几块，实际烧多久以燃料表为准。 */
    private static final int PLANNED_COAL_BURN_TICKS = 1600;
    private static final String COAL = "minecraft:coal";

    private final Set<WorkstationRecipe.Kind> kinds;
    private final ReadsRecipes recipes;
    private final WorldMemory memory;
    private final ReadsFuels fuels;
    private final RecipeRuns runs;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ItemNeeds needs;
    /** 就地摆工作站的接缝；没接上时附近没有设施就如实说没有，不放。 */
    private final SetsUpWorkstation placer;
    private final PermissionCheck permission;

    /** 只做给定几种配方的来源：合成一条路（含石切台），烧炼一条路，分开登记才分得开 via。 */
    public RecipeSource(Set<WorkstationRecipe.Kind> kinds, ReadsRecipes recipes, WorldMemory memory, ReadsFuels fuels,
            RecipeRuns runs, BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs,
            SetsUpWorkstation placer, PermissionCheck permission) {
        if (kinds.isEmpty()) throw new IllegalArgumentException("来源至少要认一种配方");
        this.kinds = Set.copyOf(kinds);
        this.recipes = recipes;
        this.memory = memory;
        this.fuels = fuels;
        this.runs = runs;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
        this.needs = needs;
        this.placer = placer;
        this.permission = permission;
    }

    /** 认所有种类配方、不就地摆设施的来源：就地摆放与种类分路接入前的老写法。 */
    public RecipeSource(ReadsRecipes recipes, WorldMemory memory, ReadsFuels fuels, RecipeRuns runs,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs) {
        this(Set.of(WorkstationRecipe.Kind.CRAFTING, WorkstationRecipe.Kind.SMELTING, WorkstationRecipe.Kind.STONECUTTING),
                recipes, memory, fuels, runs, backpack, offhand, tags, needs, null, null);
    }

    @Override public String describe() {
        return kinds.contains(WorkstationRecipe.Kind.SMELTING) && !kinds.contains(WorkstationRecipe.Kind.CRAFTING)
                ? "烧炼" : "自己做";
    }

    @Override public AcquireVia via() {
        return kinds.contains(WorkstationRecipe.Kind.CRAFTING) ? AcquireVia.CRAFT : AcquireVia.SMELT;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<WorkstationRecipe> candidates = ranked(request);
        if (candidates.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "游戏里没有做出" + request.wanted().describe() + "的配方");
        }
        WorkstationRecipe recipe = candidates.getFirst();
        // 摆得进 2×2 的合成在背包合成格里做：不用找工作台，也不用先摆一个，新世界开局做木板、工作台就靠它。
        if (inInventory(recipe)) {
            return new SourceQuote.Offer(describe(), request.count(),
                    new AcquisitionCost(0, 4 + recipe.ingredients().size()),
                    "在背包的合成格里做，不用工作台", recipe.id());
        }
        Optional<MemoryRecord> station = handyStation(recipe.kind(), context);
        if (station.isEmpty()) {
            return placementQuote(recipe, request.count(), context);
        }
        int times = timesNeeded(recipe, request.count());
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(distance(station.get(), context), 6 + recipe.ingredients().size()),
                riskNote(recipe, times, context), recipe.id());
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        List<WorkstationRecipe> candidates = ranked(request);
        WorkstationRecipe recipe = candidates.stream()
                .filter(candidate -> candidate.id().equals(offer.hint()))
                .findFirst()
                .orElse(null);
        if (recipe == null) {
            // 报价时还在、动手时查不到了：交回空，由引擎换路。
            return Optional.empty();
        }
        // 缺得一样多的几种做法排在后面备着：要"任意木板"、身上一根原木都没有时，十来种木板缺的都是一根原木，
        // 头一种一件都做不出来（附近没有樱桃原木）就换下一种，不在第一种上就把"自己做"整条划掉。
        int missing = missingFor(recipe, request.count());
        List<WorkstationRecipe> variants = new ArrayList<>(List.of(recipe));
        for (WorkstationRecipe other : candidates) {
            if (variants.size() >= MAX_VARIANTS) break;
            if (other != recipe && missingFor(other, request.count()) == missing) variants.add(other);
        }
        // 缺得一样多的做法，缺的原料近的先试：补一块木板，手边有树就够了，不为它跑去十格外挖竹子；
        // 问不出远近的做法排在问得出的后面，彼此先后不变（List.sort 稳定）。
        if (variants.size() > 1) {
            variants.sort(Comparator.comparingDouble(variant -> missingIngredientDistance(variant, request, context)));
        }
        return Optional.of(variants.size() == 1 ? attempt(recipe, request, context)
                : new Variants(request, variants, context));
    }

    // 这种做法缺的原料最近要走多远：逐样问引擎（只问价不拿），缺几样时最远的那样算远近；
    // 有一样问不出（来源还没看清现场，或哪个来源都给不了）就整条按问不出算，排在问得出的后面。
    private double missingIngredientDistance(WorkstationRecipe variant, ItemRequest request, SourceContext context) {
        double farthest = 0;
        for (WorkstationRecipe.IngredientStack ingredient : variant.ingredients()) {
            int needed = ingredient.count() * timesNeeded(variant, request.count());
            int carried = CarriedItems.matching(backpack, offhand, ingredient.item(), tags);
            if (carried >= needed) continue;
            Optional<Double> distance = needs.nearestDistanceBlocks(
                    new ItemRequest(ingredient.item(), needed - carried, "补" + variant.result().describe() + "的原料"),
                    context.permissions());
            if (distance.isEmpty()) return Double.MAX_VALUE;
            farthest = Math.max(farthest, distance.get());
        }
        return farthest;
    }

    // 用拿得到的配方排个先后，像玩家一样先看手头：原料身上缺得最少的那种优先（要"任意木板"、背包里有云杉原木，
    // 就做云杉木板，不去找竹子）；缺得一样多时，摆得进背包合成格的优先，再挑原料种类最少的。
    // 拿要的东西本身当原料的配方不算做法：给床换个颜色、给羊毛染色，做完身上的床、羊毛一件没多。
    private List<WorkstationRecipe> ranked(ItemRequest request) {
        return recipes.recipesProducing(request.wanted()).stream()
                .filter(recipe -> kinds.contains(recipe.kind()))
                .filter(recipe -> !consumesWhatIsWanted(recipe, request.wanted()))
                .sorted(Comparator.comparingInt((WorkstationRecipe candidate) -> missingFor(candidate, request.count()))
                        .thenComparingInt(candidate -> inInventory(candidate) ? 0 : 1)
                        .thenComparingInt(candidate -> candidate.ingredients().size()))
                .toList();
    }

    // 照一种配方做的一整串：摆得进背包合成格的在背包里做，否则到设施上做（没有就先摆一个）。
    private Action attempt(WorkstationRecipe recipe, ItemRequest request, SourceContext context) {
        if (inInventory(recipe)) {
            return assembleInInventory(recipe, timesNeeded(recipe, request.count()), context);
        }
        Optional<MemoryRecord> station = handyStation(recipe.kind(), context);
        return assemble(recipe, station.map(MemoryRecord::position).orElse(null),
                timesNeeded(recipe, request.count()), context);
    }

    /**
     * 几种一样缺料的做法挨个试：这一种做砸了、要的东西一件没多，就换下一种；做出过东西就照常收场，由引擎清点。
     * 都不成时把试过的几种和原因一并交代。
     */
    private final class Variants implements Action {
        private final ItemRequest request;
        private final List<WorkstationRecipe> variants;
        private final SourceContext context;
        private final List<String> failures = new ArrayList<>();
        private int index;
        private Action current;
        private int carriedBefore;

        private Variants(ItemRequest request, List<WorkstationRecipe> variants, SourceContext context) {
            this.request = request;
            this.variants = variants;
            this.context = context;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (current == null) {
                carriedBefore = CarriedItems.matching(backpack, offhand, request.wanted(), tags);
                current = attempt(variants.get(index), request, context);
            }
            ActionStatus status = current.tick(tick);
            if (!(status instanceof ActionStatus.Failed failed)) return status;
            boolean delivered = CarriedItems.matching(backpack, offhand, request.wanted(), tags) > carriedBefore;
            // 每种做法只记它自己那一句结论（缺哪几样原料）；原料为什么弄不来由拿东西的引擎收着，最外层讲一次。
            failures.add(variants.get(index).id() + "：" + failed.problem().headline());
            if (!delivered && index + 1 < variants.size()) {
                current.close();
                current = null;
                index++;
                return ActionStatus.progressed();
            }
            if (failures.size() == 1) return status;
            return ActionStatus.failed(Problem.of(failed.problem().kind(), "试了 " + failures.size() + " 种做法都没做成；"
                    + String.join("；", failures), failed.problem().suggestion()));
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) current.close();
        }

        @Override public Interruptibility interruptibility() {
            return current == null ? Interruptibility.WORKING : current.interruptibility();
        }

        @Override public String describe() {
            return current == null ? "做" + request.wanted().describe()
                    : current.describe() + "（第 " + (index + 1) + "/" + variants.size() + " 种做法）";
        }
    }

    // 这件东西是不是这条配方要的原料：烧炼时不把要烧的东西当燃料。
    private boolean isIngredientOf(WorkstationRecipe recipe, String itemId) {
        return recipe.ingredients().stream().anyMatch(ingredient -> ingredient.item().matches(itemId, tags.tagsOf(itemId)));
    }

    // 这条配方的原料里有没有一样本身就满足这次要的东西：有的话做它不会让要的东西变多。
    private boolean consumesWhatIsWanted(WorkstationRecipe recipe, WantedItem wanted) {
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            WantedItem item = ingredient.item();
            if (!item.isTag()) {
                if (wanted.matches(item.itemId(), tags.tagsOf(item.itemId()))) return true;
            } else if (wanted.isTag() ? wanted.tagId().equals(item.tagId())
                    : tags.tagsOf(wanted.itemId()).contains(item.tagId())) {
                return true;
            }
        }
        return false;
    }

    // 照这条配方做够要的件数，身上还缺几件原料（各种原料缺的加起来）。
    private int missingFor(WorkstationRecipe recipe, int count) {
        int times = timesNeeded(recipe, count);
        int missing = 0;
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            int carried = CarriedItems.matching(backpack, offhand, ingredient.item(), tags);
            missing += Math.max(0, ingredient.count() * times - carried);
        }
        return missing;
    }

    // 这条配方在不在背包合成格里做：合成配方摆得进 2×2，且动手的那一层做得了背包合成。
    private boolean inInventory(WorkstationRecipe recipe) {
        return recipe.kind() == WorkstationRecipe.Kind.CRAFTING && recipe.fitsInInventory() && runs.craftsInInventory();
    }

    // 背包合成的一整串：缺的原料逐项去弄，然后打开背包在合成格里做，不走工作台。
    private Action assembleInInventory(WorkstationRecipe recipe, int times, SourceContext context) {
        Shortfall shortfall = new Shortfall(recipe);
        List<Action> steps = shortfall.ingredients(times, context.permissions());
        Optional<Action> run = runs.runInInventory(recipe, times, context.records());
        if (run.isEmpty()) {
            return steps.isEmpty() ? nothingToDo(recipe)
                    : new StepwiseActions("为「做" + recipe.result().describe() + "」备原料", steps.toArray(Action[]::new));
        }
        steps.add(topUp(recipe, shortfall, times, context.permissions()));
        steps.add(shortfall.explaining(run.get()));
        return new StepwiseActions("备齐原料后在背包里做" + recipe.result().describe(), steps.toArray(Action[]::new));
    }

    // 备料的一整串：缺的原料逐项去弄，烧炼再备燃料，没有设施就先摆一个，最后到设施上做。
    // 备齐后差一点就在关口再补一轮（见 topUp），不带着缺口去动手。
    private Action assemble(WorkstationRecipe recipe, WorldPosition station, int times, SourceContext context) {
        Permissions permissions = context.permissions();
        Shortfall shortfall = new Shortfall(recipe);
        List<Action> steps = shortfall.ingredients(times, permissions);
        if (recipe.kind() == WorkstationRecipe.Kind.SMELTING) {
            fuelNeed(recipe, times).ifPresent(fuel -> steps.add(shortfall.tolerant(fuel, 0, permissions)));
        }
        // 备完料先看够不够做一份：一份都不够就不去摆设施、不走过去，以备料的原因收场。
        steps.add(topUp(recipe, shortfall, times, permissions));
        if (station == null) {
            // 附近没有记住的设施：先去弄一个设施方块、在身边放下，再到新设施上做。
            // 动手那一步等摆完才认新设施的位置（它的记录是摆台这一步才写下的），少这一步就只剩摆台、不做东西。
            steps.addAll(placeSteps(recipe, permissions));
            steps.add(shortfall.explaining(runAtPlacedStation(recipe, times, permissions, context.records())));
            return new StepwiseActions("就地摆下" + workstationName(recipe.kind()) + "再做"
                    + recipe.result().describe(), steps.toArray(Action[]::new));
        }
        Optional<Action> run = runs.run(recipe, station, times, permissions, context.records());
        if (run.isEmpty()) {
            // 备齐了也动不了手（设施没了、界面进不去）：整串放弃，由引擎换别的路。
            return steps.isEmpty()
                    ? nothingToDo(recipe)
                    : new StepwiseActions("为「做" + recipe.result().describe() + "」备原料",
                            steps.toArray(Action[]::new));
        }
        steps.add(shortfall.explaining(run.get()));
        return new StepwiseActions("备齐原料后去" + workstationName(recipe.kind()) + "做"
                + recipe.result().describe(), steps.toArray(Action[]::new));
    }

    // 到刚摆下的那台设施上做：动手时才从世界记忆里认它的位置。附近本来没有这台设施，
    // 它的记录是摆台那一步做完才写下的；串动作还没开始跑时就查，查到的永远是空，
    // 于是只剩摆台、不做东西，结果还像做完了。认不出位置（摆台的动作没记下）就如实失败，由引擎换路。
    private Action runAtPlacedStation(WorkstationRecipe recipe, int times, Permissions permissions,
            TaskRecords records) {
        return new Action() {
            private Action run;

            @Override public ActionStatus tick(TickContext tick) {
                if (run == null) {
                    run = runs.runAtRememberedStation(recipe, times, permissions, records).orElse(null);
                    if (run == null) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                                workstationName(recipe.kind()) + "摆下了，但记忆里没有它的位置，做不了"
                                        + recipe.result().describe()));
                    }
                }
                return run.tick(tick);
            }

            @Override public void pause() {
                if (run != null) run.pause();
            }

            @Override public void close() {
                if (run != null) run.close();
            }

            @Override public Interruptibility interruptibility() {
                return run == null ? Interruptibility.WORKING : run.interruptibility();
            }

            @Override public String describe() {
                return run == null
                        ? "到刚摆下的" + workstationName(recipe.kind()) + "跟前做" + recipe.result().describe()
                        : run.describe();
            }
        };
    }

    // 备完料的关口：身上的料够做一份就往下走；不够就把还缺的再备一轮——先备出来的中间产物被后面的
    // 步骤用掉时（做木棍用掉镐头要的木板），差的那点在这里照实补上，手上的原料还有就先补，不说原料不够。
    // 补一轮一件没多（缺的那样真弄不来）才以备料的原因收场，不围着同一处空转。
    private Action topUp(WorkstationRecipe recipe, Shortfall shortfall, int times, Permissions permissions) {
        return new Action() {
            private Action round;
            private int lastMissing = -1;

            @Override public ActionStatus tick(TickContext tick) {
                int missing = missingFor(recipe, recipe.resultCount());
                if (missing == 0) return ActionStatus.done();
                if (round == null) {
                    // 上一轮备完还是缺这么多：缺的那样真弄不来，不再围着它空转。
                    if (missing == lastMissing) {
                        return ActionStatus.failed(gateProblem(recipe, shortfall));
                    }
                    lastMissing = missing;
                    List<Action> steps = shortfall.ingredients(times, permissions);
                    if (steps.isEmpty()) return ActionStatus.failed(gateProblem(recipe, shortfall));
                    round = steps.size() == 1 ? steps.getFirst()
                            : new StepwiseActions("再补一轮做" + recipe.result().describe() + "缺的原料",
                                    steps.toArray(Action[]::new));
                }
                ActionStatus status = round.tick(tick);
                if (status instanceof ActionStatus.Done) {
                    round = null;
                    return ActionStatus.progressed();
                }
                return status;
            }

            @Override public void pause() {
                if (round != null) round.pause();
            }

            @Override public void close() {
                if (round != null) round.close();
            }

            @Override public Interruptibility interruptibility() {
                return round == null ? Interruptibility.WORKING : round.interruptibility();
            }

            @Override public String describe() {
                return round == null ? "看身上的料够不够做" + recipe.result().describe() : round.describe();
            }
        };
    }

    // 关口的失败说明：备料时记下过弄不来的就说缺哪几样；一步都没记过（步骤都做成了、量还是不够）就直说身上不够。
    private Problem gateProblem(WorkstationRecipe recipe, Shortfall shortfall) {
        return shortfall.missing.isEmpty()
                ? Problem.of(Problem.Kind.NEED_ITEM, "身上的原料不够做一份" + recipe.result().describe())
                : shortfall.problem();
    }

    /**
     * 备料弄不来的经过：一样原料弄不来，身上这样原料还够做一份就接着备别的，先把够做的做掉；
     * 连一份的量都没有就当场放弃这个做法——别的原料弄来也做不成，不白跑去弄（没有羊毛就不去砍床板的木头）。
     * 里层为什么弄不来只转述它的结论句，经过由拿东西的引擎收着，最外层讲一次。
     */
    private final class Shortfall {
        private final WorkstationRecipe recipe;
        /** 弄不来的原料：东西×件数。 */
        private final List<String> missing = new ArrayList<>();
        /** 里层没弄来的结论句。 */
        private final List<String> reasons = new ArrayList<>();

        private Shortfall(WorkstationRecipe recipe) {
            this.recipe = recipe;
        }

        // 缺的原料逐项回引擎去弄，身上已有的不再要，用途写清是为谁备的。这一次里已经知道拿不到的那样排最前：
        // 身上一份的量都没有，碰一下就放弃这个做法，不先去弄排在它前面的原料（没有白色羊毛，就不先去采染红色要的花）。
        List<Action> ingredients(int times, Permissions permissions) {
            List<WorkstationRecipe.IngredientStack> ordered = new ArrayList<>(recipe.ingredients());
            ordered.sort(Comparator.comparingInt(
                    (WorkstationRecipe.IngredientStack ingredient) -> needs.knownUnobtainable(ingredient.item()) ? 0 : 1));
            List<Action> steps = new ArrayList<>();
            for (WorkstationRecipe.IngredientStack ingredient : ordered) {
                int needed = ingredient.count() * times;
                int carried = CarriedItems.matching(backpack, offhand, ingredient.item(), tags);
                if (carried < needed) {
                    steps.add(tolerant(new ItemRequest(ingredient.item(), needed - carried,
                            "为「做" + recipe.result().describe() + "」备原料"), ingredient.count(), permissions));
                }
            }
            return steps;
        }

        // 备料的一步：回引擎去弄。perCraft 是做一份要几件，为 0 表示不按份数止损（燃料不够还能烧一部分）。
        Action tolerant(ItemRequest request, int perCraft, Permissions permissions) {
            return new Tolerant(needs.actionFor(request, permissions), request, perCraft);
        }

        // 原料没备齐、一份都做不了：结论句点名缺哪几样，展开写里层没弄来的结论。
        // 做的是什么由转述的一层写在前面（哪种做法、哪样东西），这里不再重复成品的名字。
        Problem problem() {
            StringBuilder message = new StringBuilder("原料没备齐（缺").append(String.join("、", missing))
                    .append("），一份都做不了");
            for (String reason : reasons) message.append("；").append(reason);
            return Problem.of(Problem.Kind.NEED_ITEM, message.toString());
        }

        // 动手那一步：做不下去时（料用完了）在它的结论后面补上缺哪几样，引擎写进结果。
        Action explaining(Action run) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    ActionStatus status = run.tick(context);
                    if (!(status instanceof ActionStatus.Failed failed) || missing.isEmpty()) return status;
                    Problem problem = failed.problem();
                    String headline = problem.headline();
                    StringBuilder message = new StringBuilder(headline).append("（原料没备齐：缺")
                            .append(String.join("、", missing)).append("，身上的料能做的已经做了）")
                            .append(problem.message().substring(headline.length()));
                    for (String reason : reasons) message.append("；").append(reason);
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, message.toString(),
                            problem.suggestion()));
                }
                @Override public void pause() {
                    run.pause();
                }
                @Override public void close() {
                    run.close();
                }
                @Override public Interruptibility interruptibility() {
                    return run.interruptibility();
                }
                @Override public String describe() {
                    return run.describe();
                }
            };
        }

        private final class Tolerant implements Action {
            private final Action step;
            private final ItemRequest request;
            private final int perCraft;

            private Tolerant(Action step, ItemRequest request, int perCraft) {
                this.step = step;
                this.request = request;
                this.perCraft = perCraft;
            }

            // 弄不来：记下缺什么和里层的结论句。身上这样原料还够做一份就按做完往下走，先把够做的做掉；
            // 一份的量都没有就整个做法到此为止，别的原料弄来也做不成。
            @Override public ActionStatus tick(TickContext context) {
                ActionStatus status = step.tick(context);
                if (!(status instanceof ActionStatus.Failed failed)) return status;
                missing.add(request.wanted().describe() + "×" + request.count());
                reasons.add(failed.problem().headline());
                if (perCraft > 0 && CarriedItems.matching(backpack, offhand, request.wanted(), tags) < perCraft) {
                    return ActionStatus.failed(problem());
                }
                return ActionStatus.done();
            }
            @Override public void pause() {
                step.pause();
            }
            @Override public void close() {
                step.close();
            }
            @Override public Interruptibility interruptibility() {
                return step.interruptibility();
            }
            @Override public String describe() {
                return step.describe();
            }
        }
    }

    // 摆一个设施要的几步：身上没有设施方块就先去弄一个，然后在身边放下。摆不了（没接上）时给一个当场说明问题的动作。
    private List<Action> placeSteps(WorkstationRecipe recipe, Permissions permissions) {
        String blockType = workstationBlockType(recipe.kind());
        List<Action> steps = new ArrayList<>();
        int carried = CarriedItems.matching(backpack, offhand, WantedItem.ofItem(blockType), tags);
        if (carried < 1) {
            steps.add(needs.actionFor(new ItemRequest(WantedItem.ofItem(blockType), 1,
                    "就地摆放的工作站"), permissions));
        }
        // 接缝没接上、或现场放不下时，这一步以问题收场，整串停在摆台之前，不冒充做成了。
        Optional<Action> placing = placer == null ? Optional.empty() : placer.placeNearby(blockType);
        if (placing.isEmpty()) {
            steps.add(cannotPlace(recipe));
        } else {
            placing.ifPresent(steps::add);
        }
        return steps;
    }

    // 摆不了设施的动作：一推进一步就带着问题失败，免得"备料成功"冒充摆好了台子。
    private Action cannotPlace(WorkstationRecipe recipe) {
        String name = workstationName(recipe.kind());
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                        "在身边放下" + name + "的现场动作还没接上，做不了"));
            }
            @Override public String describe() {
                return "放" + name + "的现场动作还没接上";
            }
        };
    }

    // 报价时没找到记住的设施：能就地摆就报"摆一个再做"，摆不了就如实说没有设施。
    private SourceQuote placementQuote(WorkstationRecipe recipe, int count, SourceContext context) {
        if (placer == null) {
            return new SourceQuote.Unavailable(describe(),
                    "有配方（" + recipe.id() + "），但没记得附近有" + workstationName(recipe.kind())
                            + "；用过一次才会记得它在哪");
        }
        if (context.permissions().changeBlocks() == Permissions.BlockChanges.NONE) {
            return new SourceQuote.Unavailable(describe(),
                    "有配方（" + recipe.id() + "），附近没有" + workstationName(recipe.kind())
                            + "，就地放一个需要改方块的许可，这次的许可不给");
        }
        return new SourceQuote.Offer(describe(), count,
                new AcquisitionCost(0, 8 + recipe.ingredients().size()),
                "附近没有" + workstationName(recipe.kind()) + "，会在身边放一个（消耗一个）并记下位置",
                recipe.id());
    }

    // 烧炼要的燃料：身上有烧得着的就用身上的（挑烧得最久的），一件都没有才去弄煤。
    private Optional<ItemRequest> fuelNeed(WorkstationRecipe recipe, int times) {
        int smeltTicks = times * SMELT_TICKS_PER_ITEM;
        // 身上能烧的加起来够不够：十块木板能烤好几块肉，不能只看最耐烧的一件就说要去弄煤。
        // 工具、武器这类一格一件的不算燃料，这次要烧的原料也不算。
        long burnable = 0;
        for (var stack : backpack.stacks()) {
            if (stack.maxStackSize() <= 1 || isIngredientOf(recipe, stack.itemId())) continue;
            burnable += (long) stack.count() * fuels.burnTicks(stack.itemId());
        }
        if (burnable >= smeltTicks) {
            return Optional.empty();
        }
        int coalNeeded = Math.max(1, (int) Math.ceil((double) smeltTicks / PLANNED_COAL_BURN_TICKS));
        return Optional.of(new ItemRequest(WantedItem.ofItem(COAL), coalNeeded,
                "烧炼" + recipe.result().describe() + "的燃料"));
    }

    // 没有备料要做、动手也接不上：一个当场说明问题的动作，免得"备料成功"冒充做成了。
    private Action nothingToDo(WorkstationRecipe recipe) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                        "在" + workstationName(recipe.kind()) + "上做" + recipe.result().describe()
                                + "的现场动作还没接上，做不了"));
            }
            @Override public String describe() {
                return "做" + recipe.result().describe() + "的现场动作还没接上";
            }
        };
    }

    private int timesNeeded(WorkstationRecipe recipe, int count) {
        return (int) Math.ceil((double) count / recipe.resultCount());
    }

    // 这次用哪台记得的设施：工作台离得远、又能就地摆一张时不用它，报价与动手看的是同一个结论。
    private Optional<MemoryRecord> handyStation(WorkstationRecipe.Kind kind, SourceContext context) {
        Optional<MemoryRecord> station = findStation(kind, context);
        boolean mayPlace = placer != null && context.permissions().changeBlocks() != Permissions.BlockChanges.NONE;
        if (station.isPresent() && kind == WorkstationRecipe.Kind.CRAFTING && mayPlace
                && distance(station.get(), context) > HANDY_CRAFTING_TABLE_BLOCKS) {
            return Optional.empty();
        }
        return station;
    }

    private Optional<MemoryRecord> findStation(WorkstationRecipe.Kind kind, SourceContext context) {
        String blockType = workstationBlockType(kind);
        // 记得好几台时挑离得最近的那台：世界记忆按记下的先后排，最新记的不一定最近。
        // 眼下看得到已经没了的（被挖走、被炸掉）先从记忆里划掉：不然会走过去点一块空地，整件合成白白失败，
        // 身上明明还有一张工作台也不会放下来用。
        List<MemoryRecord> remembered = memory.recordsNear(context.characterAt(), SEARCH_RADIUS_BLOCKS).stream()
                .filter(record -> record.kind() == MemoryKind.WORKSTATION)
                .filter(record -> blockType.equals(record.blockType()))
                .toList();
        List<MemoryRecord> standing = new ArrayList<>();
        for (MemoryRecord record : remembered) {
            if (placer != null && placer.gone(record.position(), blockType)) {
                memory.forget(MemoryKind.WORKSTATION, record.position());
            } else {
                standing.add(record);
            }
        }
        return standing.stream().min(Comparator.comparingDouble(record -> distance(record, context)));
    }

    private String riskNote(WorkstationRecipe recipe, int times, SourceContext context) {
        StringBuilder note = new StringBuilder("还要备齐原料：");
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            note.append(ingredient.item().describe()).append("×").append(ingredient.count() * times).append(" ");
        }
        if (recipe.kind() == WorkstationRecipe.Kind.SMELTING) {
            note.append("；烧").append(times).append("次还要燃料");
        }
        return note.toString().trim();
    }

    /** 设施的方块类型：配方在哪种设施上做，就在记忆里找哪种。 */
    private static String workstationBlockType(WorkstationRecipe.Kind kind) {
        return switch (kind) {
            case CRAFTING -> "minecraft:crafting_table";
            case SMELTING -> "minecraft:furnace";
            case STONECUTTING -> "minecraft:stonecutter";
        };
    }

    private static String workstationName(WorkstationRecipe.Kind kind) {
        return switch (kind) {
            case CRAFTING -> "工作台";
            case SMELTING -> "熔炉";
            case STONECUTTING -> "石切台";
        };
    }

    private static double distance(MemoryRecord record, SourceContext context) {
        var at = record.position();
        var here = context.characterAt();
        double dx = at.x() - here.x();
        double dy = at.y() - here.y();
        double dz = at.z() - here.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
