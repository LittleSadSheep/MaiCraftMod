// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
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
 * 一格收好几样的原料（给床换颜色要"别的颜色的任一张床"）身上有哪样用哪样，缺了挑最省的那样去弄。
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
    /** 一趟最多做几份：分批做，料不够时中间件和最后一步交替用料，不把共用的料先全做成中间件。 */
    static final int MAX_CRAFTS_PER_RUN = 16;
    /** 现做时身上每缺一件原料报价加几格（和"说不准有没有"同一把尺子）：野外现成的床先拆回来，不先打三只羊做一张。 */
    static final double SHORTFALL_BLOCKS_PER_ITEM = 32;
    /** 排远近前最多看几种做法：装了加木头的模组时木板有几十种，先全拿来按远近排，再取最近的几种试。 */
    private static final int VARIANT_POOL = 64;

    /** 备燃料时的规划数字：一块煤烧 1600 刻，按它算要带几块，实际烧多久以燃料表为准。 */
    private static final int PLANNED_COAL_BURN_TICKS = 1600;
    private static final String COAL = "minecraft:coal";
    private static final String LOGS_TAG = "minecraft:logs";
    private static final String PLANKS_TAG = "minecraft:planks";
    /** 一块木板烧多久（刻）：一块半件的量。 */
    private static final int PLANK_BURN_TICKS = 300;

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
    /** 可以互换的原料怎么数、缺了去弄哪一样。 */
    private final IngredientChoice choice;
    /** 记得的工作站：挑哪台、烧炉子正忙时换哪几台。 */
    private final RememberedStations stations;
    /** 拿东西的引擎此刻在推进的那一串请求（外层在前）；没接上为空串。 */
    private Supplier<List<ItemRequest>> chain = List::of;

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
        this.choice = new IngredientChoice(backpack, offhand, tags, needs);
        this.placer = placer;
        this.permission = permission;
        this.stations = new RememberedStations(memory, placer, SEARCH_RADIUS_BLOCKS);
    }

    /** 认所有种类配方、不就地摆设施的来源：就地摆放与种类分路接入前的老写法。 */
    public RecipeSource(ReadsRecipes recipes, WorldMemory memory, ReadsFuels fuels, RecipeRuns runs,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs) {
        this(Set.of(WorkstationRecipe.Kind.CRAFTING, WorkstationRecipe.Kind.SMELTING, WorkstationRecipe.Kind.BLASTING,
                        WorkstationRecipe.Kind.SMOKING, WorkstationRecipe.Kind.STONECUTTING),
                recipes, memory, fuels, runs, backpack, offhand, tags, needs, null, null);
    }

    @Override public String describe() {
        return kinds.contains(WorkstationRecipe.Kind.SMELTING) && !kinds.contains(WorkstationRecipe.Kind.CRAFTING)
                ? "烧炼" : "自己做";
    }

    /** 接上拿东西的引擎：它此刻在推进的那一串请求，备料时不把外层正要的东西当原料用掉。 */
    public void watchChain(Supplier<List<ItemRequest>> chain) {
        this.chain = Objects.requireNonNull(chain, "chain");
    }

    @Override public AcquireVia via() {
        return kinds.contains(WorkstationRecipe.Kind.CRAFTING) ? AcquireVia.CRAFT : AcquireVia.SMELT;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<WorkstationRecipe> candidates = ranked(request, context);
        if (candidates.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    StationFallbacks.noRecipeReason(request.wanted(), recipes.recipesProducing(request.wanted()), kinds));
        }
        WorkstationRecipe recipe = candidates.getFirst();
        // 身上缺几件原料就把先去弄它们的麻烦算进报价：缺料的做法排在现成的、近的来路后面。
        double shortfall = missingFor(recipe, request.count()) * SHORTFALL_BLOCKS_PER_ITEM;
        // 摆得进 2×2 的合成在背包合成格里做：不用找工作台，也不用先摆一个，新世界开局做木板、工作台就靠它。
        if (inInventory(recipe)) {
            return new SourceQuote.Offer(describe(), request.count(),
                    new AcquisitionCost(0, 4 + recipe.ingredients().size(), shortfall),
                    "在背包的合成格里做，不用工作台", recipe.id());
        }
        Optional<MemoryRecord> station = handyStation(recipe.kind(), context);
        if (station.isEmpty()) {
            return placementQuote(recipe, request.count(), context, shortfall);
        }
        int times = timesNeeded(recipe, request.count());
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(distance(station.get(), context), 6 + recipe.ingredients().size(), shortfall),
                riskNote(recipe, times, context), recipe.id());
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        List<WorkstationRecipe> candidates = ranked(request, context);
        WorkstationRecipe recipe = candidates.stream()
                .filter(candidate -> candidate.id().equals(offer.hint()))
                .findFirst()
                .orElse(null);
        if (recipe == null) {
            // 报价时还在、动手时查不到了：交回空，由引擎换路。
            return Optional.empty();
        }
        List<WorkstationRecipe> variants = variantsFor(recipe, candidates, request, context);
        // 排头两种原料一样近（多半是身上的 AE 终端这五分钟没开过，每种木头都报"看一眼"）：先打开看一眼再挑，
        // 不按配方表的顺序先试橡木、去砍附近的橡树。原先 AE 里堆着云杉原木，照样跑去砍橡树。
        if (variants.size() > 1 && missingIngredientDistance(variants.get(0), request, context)
                == missingIngredientDistance(variants.get(1), request, context)) {
            Optional<Action> look = firstMissing(variants.get(0), request, context)
                    .flatMap(ingredient -> needs.lookAround(ingredient, context.permissions()));
            if (look.isPresent()) {
                return Optional.of(new LooksBeforeChoosing(look.get(),
                        () -> run(variantsFor(recipe, ranked(request, context), request, context), recipe, request, context)));
            }
        }
        return Optional.of(run(variants, recipe, request, context));
    }

    private Action run(List<WorkstationRecipe> variants, WorkstationRecipe recipe, ItemRequest request,
            SourceContext context) {
        return variants.size() <= 1 ? attempt(recipe, request, context) : new Variants(request, variants, context);
    }

    // 这种做法缺的第一样原料，按还缺几件发请求；什么都不缺给空。
    private Optional<ItemRequest> firstMissing(WorkstationRecipe variant, ItemRequest request, SourceContext context) {
        for (WorkstationRecipe.IngredientStack ingredient : variant.ingredients()) {
            int needed = ingredient.count() * timesNeeded(variant, request.count());
            int carried = choice.carried(ingredient);
            if (carried < needed) {
                return Optional.of(new ItemRequest(pick(ingredient, needed - carried, context), needed - carried,
                        "补" + variant.result().describe() + "的原料"));
            }
        }
        return Optional.empty();
    }

    // 这一格缺了去弄哪一样：可以互换的原料身上有的先用，没有挑最省的；现做缺几件按这个来源最省的做法算。
    private WantedItem pick(WorkstationRecipe.IngredientStack ingredient, int needed, SourceContext context) {
        return choice.pick(ingredient, needed, context, (asked, at) -> ranked(asked, at).stream()
                .mapToInt(recipe -> missingFor(recipe, asked.count())).min().orElse(Integer.MAX_VALUE));
    }

    // 缺得一样多的几种做法排在后面备着：要"任意木板"、身上一根原木都没有时，十来种木板缺的都是一根原木，
    // 头一种一件都做不出来（附近没有樱桃原木）就换下一种，不在第一种上就把"自己做"整条划掉。
    private List<WorkstationRecipe> variantsFor(WorkstationRecipe recipe, List<WorkstationRecipe> candidates,
            ItemRequest request, SourceContext context) {
        int missing = missingFor(recipe, request.count());
        List<WorkstationRecipe> variants = new ArrayList<>(List.of(recipe));
        for (WorkstationRecipe other : candidates) {
            if (variants.size() >= VARIANT_POOL) break;
            if (other != recipe && missingFor(other, request.count()) == missing) variants.add(other);
        }
        // 缺得一样多的做法，缺的原料近的先试：补一块木板，手边有树就够了，不为它跑去十格外挖竹子；
        // 问不出远近的做法排在问得出的后面，彼此先后不变（List.sort 稳定）。先排远近再取前几种：
        // 原先先截前十二种再排，装了加木头的模组时附近的云杉、白桦排不进前十二，木板说"哪儿都拿不到"。
        if (variants.size() > 1) {
            Map<WorkstationRecipe, Double> distances = new HashMap<>();
            for (WorkstationRecipe variant : variants) distances.put(variant, missingIngredientDistance(variant, request, context));
            variants.sort(MaterialPreference.first(preferred(request), tags).thenComparingDouble(distances::get));
            if (variants.size() > MAX_VARIANTS) variants = new ArrayList<>(variants.subList(0, MAX_VARIANTS));
        }
        return variants;
    }

    // 这种做法缺的原料最近要走多远：逐样问引擎（只问价不拿），缺几样时最远的那样算远近；
    // 有一样问不出（来源还没看清现场，或哪个来源都给不了）就整条按问不出算，排在问得出的后面。
    private double missingIngredientDistance(WorkstationRecipe variant, ItemRequest request, SourceContext context) {
        double farthest = 0;
        for (WorkstationRecipe.IngredientStack ingredient : variant.ingredients()) {
            int needed = ingredient.count() * timesNeeded(variant, request.count());
            int carried = choice.carried(ingredient);
            if (carried >= needed) continue;
            Optional<Double> distance = needs.nearestDistanceBlocks(
                    new ItemRequest(pick(ingredient, needed - carried, context), needed - carried,
                            "补" + variant.result().describe() + "的原料"),
                    context.permissions());
            if (distance.isEmpty()) return Double.MAX_VALUE;
            farthest = Math.max(farthest, distance.get());
        }
        return farthest;
    }

    // 用拿得到的配方排个先后，像玩家一样先看手头：原料身上缺得最少的那种优先（要"任意木板"、背包里有云杉原木，
    // 就做云杉木板，不去找竹子）；缺得一样多时，摆得进背包合成格的优先，再挑原料种类最少的。
    // 拿要的东西本身当原料的配方不算做法：给床换个颜色、给羊毛染色，做完身上的床、羊毛一件没多。
    // 能用的做法按先后排：高炉、烟熏炉这类只用现成的，附近没记得一台就不算做法（不会为了快一倍去凑一台）；
    // 缺料一样、原料一样时烧得快的在前——记得高炉就把矿石拿去高炉烧，没有照旧用熔炉。
    private List<WorkstationRecipe> ranked(ItemRequest request, SourceContext context) {
        return recipes.recipesProducing(request.wanted()).stream()
                .filter(recipe -> kinds.contains(recipe.kind()))
                .filter(recipe -> recipe.kind().placeable() || findStation(recipe.kind(), context).isPresent())
                .filter(recipe -> !consumesWhatIsWanted(recipe, request.wanted()))
                .filter(recipe -> !consumesOuterWanted(recipe))
                .sorted(MaterialPreference.first(preferred(request), tags)
                        .thenComparingInt((WorkstationRecipe candidate) -> missingFor(candidate, request.count()))
                        .thenComparingInt(candidate -> inInventory(candidate) ? 0 : 1)
                        .thenComparingInt(candidate -> candidate.ingredients().size())
                        .thenComparingInt(candidate -> candidate.kind().plannedCookTicks()))
                .toList();
    }

    // LLM 说的材料倾向：这次请求点的，加上外层还在做的请求点的（备料时沿配方链往下传）。
    private Set<String> preferred(ItemRequest request) {
        Set<String> prefer = new HashSet<>(request.prefer());
        for (ItemRequest outer : chain.get()) prefer.addAll(outer.prefer());
        return prefer;
    }

    // 照一种配方做的一整串：摆得进背包合成格的在背包里做，否则到设施上做（没有就先摆一个）。
    // 一次最多做 MAX_CRAFTS_PER_RUN 份，做完由引擎清点、还差再来：料不够时不会先把共用的料全做成中间件，
    // 最后一步没料可用（实机要 50 个大齿轮，13 根原木全做成了齿轮，只出了 1 个大齿轮）。
    private Action attempt(WorkstationRecipe recipe, ItemRequest request, SourceContext context) {
        int times = Math.min(timesNeeded(recipe, request.count()), MAX_CRAFTS_PER_RUN);
        if (inInventory(recipe)) {
            return assembleInInventory(recipe, times, context);
        }
        Optional<MemoryRecord> station = handyStation(recipe.kind(), context);
        return assemble(recipe, station.map(MemoryRecord::position).orElse(null), times, context);
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
        return recipe.ingredients().stream().anyMatch(ingredient -> ingredient.accepts(itemId, tags.tagsOf(itemId)));
    }

    // 这条配方的原料里有没有一样本身就满足这次要的东西：有的话做它不会让要的东西变多。
    // 外层正要的东西当这一步的原料：为了做安山合金去弄合金块，又拿身上刚做好的合金压成块——白白压掉。
    // 实机要 38 个安山合金，引擎选了"合金块拆成合金"，把做好的合金压成一个块，拆的配方没解锁，76 块安山岩做的合金进了块里。
    private boolean consumesOuterWanted(WorkstationRecipe recipe) {
        for (ItemRequest outer : chain.get()) {
            if (consumesWhatIsWanted(recipe, outer.wanted())) return true;
        }
        return false;
    }

    private boolean consumesWhatIsWanted(WorkstationRecipe recipe, WantedItem wanted) {
        return recipe.ingredients().stream().anyMatch(ingredient -> choice.satisfies(ingredient, wanted));
    }

    // 照这条配方做够要的件数，身上还缺几件原料（各种原料缺的加起来）。
    private int missingFor(WorkstationRecipe recipe, int count) {
        int times = timesNeeded(recipe, count);
        int missing = 0;
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            missing += Math.max(0, ingredient.count() * times - choice.carried(ingredient));
        }
        return missing;
    }

    // 这条配方在不在背包合成格里做：合成配方摆得进 2×2，且动手的那一层做得了背包合成。
    private boolean inInventory(WorkstationRecipe recipe) {
        return recipe.kind() == WorkstationRecipe.Kind.CRAFTING && recipe.fitsInInventory() && runs.craftsInInventory();
    }

    // 背包合成的一整串：缺的原料逐项去弄，然后打开背包在合成格里做，不走工作台。
    private Action assembleInInventory(WorkstationRecipe recipe, int times, SourceContext context) {
        Shortfall shortfall = new Shortfall(recipe, context);
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
        Shortfall shortfall = new Shortfall(recipe, context);
        List<Action> steps = shortfall.ingredients(times, permissions);
        if (recipe.kind().cooks()) {
            fuelNeed(recipe, times).ifPresent(fuel -> steps.add(shortfall.tolerant(fuel, null, 0, permissions)));
        }
        // 备完料先看够不够做一份：一份都不够就不去摆设施、不走过去，以备料的原因收场。
        steps.add(topUp(recipe, shortfall, times, permissions));
        if (station == null && !recipe.kind().placeable()) {
            // 报价时记得的高炉、烟熏炉动手时不见了：不会为它去摆一台，整串以问题收场，由引擎换别的路（熔炉照旧能用）。
            steps.add(StationFallbacks.stationGone(recipe.kind()));
            return new StepwiseActions("记得的" + recipe.kind().displayName() + "不见了",
                    steps.toArray(Action[]::new));
        }
        if (station == null) {
            // 附近没有记住的设施：先去弄一个设施方块、在身边放下，再到新设施上做。
            // 动手那一步等摆完才认新设施的位置（它的记录是摆台这一步才写下的），少这一步就只剩摆台、不做东西。
            steps.addAll(placeSteps(recipe, permissions));
            steps.add(shortfall.explaining(runAtPlacedStation(recipe, times, permissions, context.records())));
            return new StepwiseActions("就地摆下" + recipe.kind().displayName() + "再做"
                    + recipe.result().describe(), steps.toArray(Action[]::new));
        }
        // 烧炉子时把记得的同种炉子都按远近交过去：挑中的那台点开发现在烧别人的东西，关上换下一台。
        Optional<Action> run = recipe.kind().cooks()
                ? runs.runAtAnyOf(recipe, stations.nearestFirst(recipe.kind(), station, context.characterAt()), times,
                        permissions, context.records())
                : runs.run(recipe, station, times, permissions, context.records());
        if (run.isEmpty()) {
            // 备齐了也动不了手（设施没了、界面进不去）：整串放弃，由引擎换别的路。
            return steps.isEmpty()
                    ? nothingToDo(recipe)
                    : new StepwiseActions("为「做" + recipe.result().describe() + "」备原料",
                            steps.toArray(Action[]::new));
        }
        steps.add(shortfall.explaining(run.get()));
        return new StepwiseActions("备齐原料后去" + recipe.kind().displayName() + "做"
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
                                recipe.kind().displayName() + "摆下了，但记忆里没有它的位置，做不了"
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
                        ? "到刚摆下的" + recipe.kind().displayName() + "跟前做" + recipe.result().describe()
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
        /** 这次动手时的现场：可以互换的原料挑哪一样时按它问代价。 */
        private final SourceContext context;
        /** 弄不来的原料：东西×件数。 */
        private final List<String> missing = new ArrayList<>();
        /** 里层没弄来的结论句。 */
        private final List<String> reasons = new ArrayList<>();

        private Shortfall(WorkstationRecipe recipe, SourceContext context) {
            this.recipe = recipe;
            this.context = context;
        }

        // 缺的原料逐项回引擎去弄，身上已有的不再要，用途写清是为谁备的。这一次里已经知道拿不到的那样排最前：
        // 身上一份的量都没有，碰一下就放弃这个做法，不先去弄排在它前面的原料（没有白色羊毛，就不先去采染红色要的花）。
        List<Action> ingredients(int times, Permissions permissions) {
            List<WorkstationRecipe.IngredientStack> ordered = new ArrayList<>(recipe.ingredients());
            ordered.sort(Comparator.comparingInt((WorkstationRecipe.IngredientStack ingredient) ->
                    ingredient.options().stream().allMatch(needs::knownUnobtainable) ? 0 : 1));
            List<Action> steps = new ArrayList<>();
            for (WorkstationRecipe.IngredientStack ingredient : ordered) {
                int needed = ingredient.count() * times;
                int carried = choice.carried(ingredient);
                if (carried < needed) {
                    // 可以互换的原料身上没有就挑最省的那样去弄，不只认配方里排第一的。
                    steps.add(tolerant(new ItemRequest(pick(ingredient, needed - carried, context), needed - carried,
                            "为「做" + recipe.result().describe() + "」备原料"), ingredient, ingredient.count(), permissions));
                }
            }
            return steps;
        }

        // 备料的一步：回引擎去弄。perCraft 是做一份要几件，为 0 表示不按份数止损（燃料不够还能烧一部分）；
        // ingredient 是这一步备的哪一格原料（燃料没有为空），身上够不够一份按这一格收的几样一起数。
        Action tolerant(ItemRequest request, WorkstationRecipe.IngredientStack ingredient, int perCraft,
                Permissions permissions) {
            return new Tolerant(needs.actionFor(request, permissions), request, ingredient, perCraft);
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
            private final WorkstationRecipe.IngredientStack ingredient;
            private final int perCraft;

            private Tolerant(Action step, ItemRequest request, WorkstationRecipe.IngredientStack ingredient,
                    int perCraft) {
                this.step = step;
                this.request = request;
                this.ingredient = ingredient;
                this.perCraft = perCraft;
            }

            // 弄不来：记下缺什么和里层的结论句。身上这样原料还够做一份就按做完往下走，先把够做的做掉；
            // 一份的量都没有就整个做法到此为止，别的原料弄来也做不成。
            @Override public ActionStatus tick(TickContext context) {
                ActionStatus status = step.tick(context);
                if (!(status instanceof ActionStatus.Failed failed)) return status;
                missing.add(request.wanted().describe() + "×" + request.count());
                reasons.add(failed.problem().headline());
                int onHand = ingredient == null ? CarriedItems.matching(backpack, offhand, request.wanted(), tags)
                        : choice.carried(ingredient);
                if (perCraft > 0 && onHand < perCraft) {
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
        String blockType = recipe.kind().blockType();
        List<Action> steps = new ArrayList<>();
        int carried = CarriedItems.matching(backpack, offhand, WantedItem.ofItem(blockType), tags);
        if (carried < 1) {
            steps.add(needs.actionFor(new ItemRequest(WantedItem.ofItem(blockType), 1,
                    "就地摆放的工作站"), permissions));
        }
        // 接缝没接上、或现场放不下时，这一步以问题收场，整串停在摆台之前，不冒充做成了。
        Optional<Action> placing = placer == null ? Optional.empty() : placer.placeNearby(blockType);
        if (placing.isEmpty()) {
            steps.add(StationFallbacks.cannotPlace(recipe.kind()));
        } else {
            placing.ifPresent(steps::add);
        }
        return steps;
    }

    // 报价时没找到记住的设施：能就地摆就报"摆一个再做"，摆不了就如实说没有设施。
    private SourceQuote placementQuote(WorkstationRecipe recipe, int count, SourceContext context, double shortfall) {
        if (placer == null) {
            return new SourceQuote.Unavailable(describe(),
                    "有配方（" + recipe.id() + "），但没记得附近有" + recipe.kind().displayName()
                            + "；用过一次才会记得它在哪");
        }
        if (context.permissions().changeBlocks() == Permissions.BlockChanges.NONE) {
            return new SourceQuote.Unavailable(describe(),
                    "有配方（" + recipe.id() + "），附近没有" + recipe.kind().displayName()
                            + "，就地放一个需要改方块的许可，这次的许可不给");
        }
        return new SourceQuote.Offer(describe(), count,
                new AcquisitionCost(0, 8 + recipe.ingredients().size(), shortfall),
                "附近没有" + recipe.kind().displayName() + "，会在身边放一个（消耗一个）并记下位置",
                recipe.id());
    }

    // 烧炼要的燃料：身上有烧得着的就用身上的（挑烧得最久的），一件都没有才去弄煤。
    private Optional<ItemRequest> fuelNeed(WorkstationRecipe recipe, int times) {
        // 燃料表是熔炉口径；高炉、烟熏炉烧得快、燃料也耗得快，按熔炉口径一件仍是 200 刻。
        int smeltTicks = times * recipe.kind().fuelTicksPerItem();
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
        // 身上有原木（又不全是这次要烧的）：拿原木做木板当燃料，开局就这么烧——要烧木炭时再去弄煤只会转圈
        // （实机做火把缺木炭，揣着原木却因为"没有燃料"烧不了木炭）。没有原木才去弄煤。
        long spareLogs = 0;
        boolean burnsLogs = false;
        for (var stack : backpack.stacks()) {
            if (!tags.tagsOf(stack.itemId()).contains(LOGS_TAG)) continue;
            spareLogs += stack.count();
            burnsLogs |= isIngredientOf(recipe, stack.itemId());
        }
        // 这一炉本来就烧原木（烧木炭）：留够要烧的那几根，剩下的才拿去做木板。
        if (burnsLogs) spareLogs -= times;
        long missingTicks = smeltTicks - burnable;
        if (spareLogs > 0) {
            int planksNeeded = Math.max(1, (int) Math.ceil((double) missingTicks / PLANK_BURN_TICKS));
            return Optional.of(new ItemRequest(WantedItem.ofTag(PLANKS_TAG), planksNeeded,
                    "烧炼" + recipe.result().describe() + "的燃料"));
        }
        int coalNeeded = Math.max(1, (int) Math.ceil((double) missingTicks / PLANNED_COAL_BURN_TICKS));
        return Optional.of(new ItemRequest(WantedItem.ofItem(COAL), coalNeeded,
                "烧炼" + recipe.result().describe() + "的燃料"));
    }

    // 没有备料要做、动手也接不上：一个当场说明问题的动作，免得"备料成功"冒充做成了。
    private Action nothingToDo(WorkstationRecipe recipe) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                        "在" + recipe.kind().displayName() + "上做" + recipe.result().describe()
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
        return stations.nearest(kind, context.characterAt());
    }

    private String riskNote(WorkstationRecipe recipe, int times, SourceContext context) {
        StringBuilder note = new StringBuilder("还要备齐原料：");
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            note.append(ingredient.describe()).append("×").append(ingredient.count() * times).append(" ");
        }
        if (recipe.kind().cooks()) {
            note.append("；").append(recipe.kind().displayName()).append("烧").append(times).append("次还要燃料");
        }
        return note.toString().trim();
    }

    private static double distance(MemoryRecord record, SourceContext context) {
        return RememberedStations.distance(record.position(), context.characterAt());
    }
}
