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
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 做出来的来源：合成、烧炼、石切台同走一条路——配方从世界真实的配方管理器读（含模组配方），
 * 设施从世界记忆里记过的工作站里找（用过一台才会记得一台）。原料缺多少、烧炼要多少燃料，
 * 递归回到拿到物品的引擎去弄，用途标签写清是为谁备的；备齐了才去设施上动手做。
 */
public final class RecipeSource implements ItemSource {

    /** 找工作站的半径：再远就不算顺手，宁可去采原料。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;
    /**
     * 记得的工作台比这远就不跑过去了：一张工作台只要四块木板，玩家会就地再摆一张，
     * 不会为做一把镐子走回几十格外的老地方（路上还要搭路、绕悬崖）。熔炉、石切台造价高，照旧走过去用。
     */
    public static final int HANDY_CRAFTING_TABLE_BLOCKS = 16;

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
        List<WorkstationRecipe> candidates = recipes.recipesProducing(request.wanted()).stream()
                .filter(recipe -> kinds.contains(recipe.kind()))
                .toList();
        if (candidates.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "游戏里没有做出" + request.wanted().describe() + "的配方");
        }
        // 多种做法时像玩家一样先看手头：原料身上缺得最少的那种优先（要"任意木板"、背包里有云杉原木，
        // 就做云杉木板，不去找竹子）；缺得一样多时，摆得进背包合成格的优先，再挑原料种类最少的。
        WorkstationRecipe recipe = candidates.stream()
                .sorted(Comparator.comparingInt((WorkstationRecipe candidate) -> missingFor(candidate, request.count()))
                        .thenComparingInt(candidate -> inInventory(candidate) ? 0 : 1)
                        .thenComparingInt(candidate -> candidate.ingredients().size()))
                .toList().getFirst();
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
        WorkstationRecipe recipe = recipes.recipesProducing(request.wanted()).stream()
                .filter(candidate -> candidate.id().equals(offer.hint()))
                .findFirst()
                .orElse(null);
        if (recipe == null) {
            // 报价时还在、动手时查不到了：交回空，由引擎换路。
            return Optional.empty();
        }
        if (inInventory(recipe)) {
            return Optional.of(assembleInInventory(recipe, timesNeeded(recipe, request.count()), context));
        }
        Optional<MemoryRecord> station = handyStation(recipe.kind(), context);
        return Optional.of(assemble(recipe,
                station.map(MemoryRecord::position).orElse(null),
                timesNeeded(recipe, request.count()), context));
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
        List<Action> steps = ingredientSteps(recipe, times, context.permissions());
        Optional<Action> run = runs.runInInventory(recipe, times);
        if (run.isEmpty()) {
            return steps.isEmpty() ? nothingToDo(recipe)
                    : new StepwiseActions("为「做" + recipe.result().describe() + "」备原料", steps.toArray(Action[]::new));
        }
        steps.add(run.get());
        return new StepwiseActions("备齐原料后在背包里做" + recipe.result().describe(), steps.toArray(Action[]::new));
    }

    // 缺的原料逐项回引擎去弄：身上已有的不再要，用途写清是为谁备的。
    private List<Action> ingredientSteps(WorkstationRecipe recipe, int times, Permissions permissions) {
        List<Action> steps = new ArrayList<>();
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            int needed = ingredient.count() * times;
            int carried = CarriedItems.matching(backpack, offhand, ingredient.item(), tags);
            if (carried < needed) {
                steps.add(needs.actionFor(new ItemRequest(ingredient.item(), needed - carried,
                        "为「做" + recipe.result().describe() + "」备原料"), permissions));
            }
        }
        return steps;
    }

    // 备料的一整串：缺的原料逐项去弄，烧炼再备燃料，没有设施就先摆一个，最后到设施上做。
    private Action assemble(WorkstationRecipe recipe, WorldPosition station, int times, SourceContext context) {
        Permissions permissions = context.permissions();
        List<Action> steps = ingredientSteps(recipe, times, permissions);
        if (recipe.kind() == WorkstationRecipe.Kind.SMELTING) {
            steps.addAll(fuelSteps(recipe, times, permissions));
        }
        if (station == null) {
            // 附近没有记住的设施：先去弄一个设施方块、在身边放下，再到新设施上做。
            steps.addAll(placeSteps(recipe, permissions));
            Optional<Action> run = runs.runAtRememberedStation(recipe, times, permissions);
            if (run.isEmpty()) {
                return steps.isEmpty()
                        ? nothingToDo(recipe)
                        : new StepwiseActions("为「做" + recipe.result().describe() + "」备料",
                                steps.toArray(Action[]::new));
            }
            steps.add(run.get());
            return new StepwiseActions("就地摆下" + workstationName(recipe.kind()) + "再做"
                    + recipe.result().describe(), steps.toArray(Action[]::new));
        }
        Optional<Action> run = runs.run(recipe, station, times, permissions);
        if (run.isEmpty()) {
            // 备齐了也动不了手（设施没了、界面进不去）：整串放弃，由引擎换别的路。
            return steps.isEmpty()
                    ? nothingToDo(recipe)
                    : new StepwiseActions("为「做" + recipe.result().describe() + "」备原料",
                            steps.toArray(Action[]::new));
        }
        steps.add(run.get());
        return new StepwiseActions("备齐原料后去" + workstationName(recipe.kind()) + "做"
                + recipe.result().describe(), steps.toArray(Action[]::new));
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
    private List<Action> fuelSteps(WorkstationRecipe recipe, int times, Permissions permissions) {
        int smeltTicks = times * SMELT_TICKS_PER_ITEM;
        int bestBurn = 0;
        for (var stack : backpack.stacks()) {
            bestBurn = Math.max(bestBurn, fuels.burnTicks(stack.itemId()));
        }
        if (bestBurn >= smeltTicks) {
            return List.of();
        }
        int coalNeeded = Math.max(1, (int) Math.ceil((double) smeltTicks / PLANNED_COAL_BURN_TICKS));
        return List.of(needs.actionFor(new ItemRequest(WantedItem.ofItem(COAL), coalNeeded,
                "烧炼" + recipe.result().describe() + "的燃料"), permissions));
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
        return memory.recordsNear(context.characterAt(), SEARCH_RADIUS_BLOCKS).stream()
                .filter(record -> record.kind() == MemoryKind.WORKSTATION)
                .filter(record -> blockType.equals(record.blockType()))
                .min(Comparator.comparingDouble(record -> distance(record, context)));
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
