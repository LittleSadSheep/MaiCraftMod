// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
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

/**
 * 做出来的来源：合成、烧炼、石切台同走一条路——配方从世界真实的配方管理器读（含模组配方），
 * 设施从世界记忆里记过的工作站里找（用过一台才会记得一台）。原料缺多少、烧炼要多少燃料，
 * 递归回到拿到物品的引擎去弄，用途标签写清是为谁备的；备齐了才去设施上动手做。
 */
public final class RecipeSource implements ItemSource {

    /** 找工作站的半径：再远就不算顺手，宁可去采原料。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;

    /** 烧炼一件东西要烧多少刻：原版熔炉的固定速度。 */
    private static final int SMELT_TICKS_PER_ITEM = 200;
    /** 备燃料时的规划数字：一块煤烧 1600 刻，按它算要带几块，实际烧多久以燃料表为准。 */
    private static final int PLANNED_COAL_BURN_TICKS = 1600;
    private static final String COAL = "minecraft:coal";

    private final Set<RecipeView.Kind> kinds;
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
    public RecipeSource(Set<RecipeView.Kind> kinds, ReadsRecipes recipes, WorldMemory memory, ReadsFuels fuels,
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
        this(Set.of(RecipeView.Kind.CRAFTING, RecipeView.Kind.SMELTING, RecipeView.Kind.STONECUTTING),
                recipes, memory, fuels, runs, backpack, offhand, tags, needs, null, null);
    }

    @Override public String describe() {
        return kinds.contains(RecipeView.Kind.SMELTING) && !kinds.contains(RecipeView.Kind.CRAFTING)
                ? "烧炼" : "自己做";
    }

    @Override public String route() {
        return kinds.contains(RecipeView.Kind.CRAFTING) ? AcquireRoutes.CRAFT : AcquireRoutes.SMELT;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<RecipeView> candidates = recipes.recipesProducing(request.wanted()).stream()
                .filter(recipe -> kinds.contains(recipe.kind()))
                .toList();
        if (candidates.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "游戏里没有做出" + request.wanted().describe() + "的配方");
        }
        // 多种做法时挑原料种类最少的一种：备料越简单，路上出的岔子越少。
        RecipeView recipe = candidates.stream()
                .sorted((a, b) -> Integer.compare(a.ingredients().size(), b.ingredients().size()))
                .toList().getFirst();
        Optional<MemoryRecord> station = findStation(recipe.kind(), context);
        if (station.isEmpty()) {
            return placementQuote(recipe, request.count(), context);
        }
        int times = timesNeeded(recipe, request.count());
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(distance(station.get(), context), 6 + recipe.ingredients().size()),
                riskNote(recipe, times, context), recipe.id());
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        RecipeView recipe = recipes.recipesProducing(request.wanted()).stream()
                .filter(candidate -> candidate.id().equals(offer.hint()))
                .findFirst()
                .orElse(null);
        if (recipe == null) {
            // 报价时还在、动手时查不到了：交回空，由引擎换路。
            return Optional.empty();
        }
        Optional<MemoryRecord> station = findStation(recipe.kind(), context);
        return Optional.of(assemble(recipe,
                station.map(MemoryRecord::position).orElse(null),
                timesNeeded(recipe, request.count()), context));
    }

    // 备料的一整串：缺的原料逐项去弄，烧炼再备燃料，没有设施就先摆一个，最后到设施上做。
    private Action assemble(RecipeView recipe, WorldPosition station, int times, SourceContext context) {
        Permissions permissions = context.permissions();
        List<Action> steps = new ArrayList<>();
        for (RecipeView.IngredientStack ingredient : recipe.ingredients()) {
            int needed = ingredient.count() * times;
            int carried = CarriedItems.matching(backpack, offhand, ingredient.item(), tags);
            if (carried < needed) {
                steps.add(needs.actionFor(new ItemRequest(ingredient.item(), needed - carried,
                        "为「做" + recipe.result().describe() + "」备原料"), permissions));
            }
        }
        if (recipe.kind() == RecipeView.Kind.SMELTING) {
            steps.addAll(fuelSteps(recipe, times, permissions));
        }
        if (station == null) {
            // 附近没有记住的设施：先去弄一个设施方块、在身边放下，再到新设施上做。
            steps.addAll(placeSteps(recipe, permissions));
            Optional<Action> run = runs.runAtRememberedStation(recipe, times);
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
        Optional<Action> run = runs.run(recipe, station, times);
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
    private List<Action> placeSteps(RecipeView recipe, Permissions permissions) {
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
    private Action cannotPlace(RecipeView recipe) {
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
    private SourceQuote placementQuote(RecipeView recipe, int count, SourceContext context) {
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
    private List<Action> fuelSteps(RecipeView recipe, int times, Permissions permissions) {
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
    private Action nothingToDo(RecipeView recipe) {
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

    private int timesNeeded(RecipeView recipe, int count) {
        return (int) Math.ceil((double) count / recipe.resultCount());
    }

    private Optional<MemoryRecord> findStation(RecipeView.Kind kind, SourceContext context) {
        String blockType = workstationBlockType(kind);
        return memory.recordsNear(context.characterAt(), SEARCH_RADIUS_BLOCKS).stream()
                .filter(record -> record.kind() == MemoryKind.WORKSTATION)
                .filter(record -> blockType.equals(record.blockType()))
                .findFirst();
    }

    private String riskNote(RecipeView recipe, int times, SourceContext context) {
        StringBuilder note = new StringBuilder("还要备齐原料：");
        for (RecipeView.IngredientStack ingredient : recipe.ingredients()) {
            note.append(ingredient.item().describe()).append("×").append(ingredient.count() * times).append(" ");
        }
        if (recipe.kind() == RecipeView.Kind.SMELTING) {
            note.append("；烧").append(times).append("次还要燃料");
        }
        return note.toString().trim();
    }

    /** 设施的方块类型：配方在哪种设施上做，就在记忆里找哪种。 */
    private static String workstationBlockType(RecipeView.Kind kind) {
        return switch (kind) {
            case CRAFTING -> "minecraft:crafting_table";
            case SMELTING -> "minecraft:furnace";
            case STONECUTTING -> "minecraft:stonecutter";
        };
    }

    private static String workstationName(RecipeView.Kind kind) {
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
