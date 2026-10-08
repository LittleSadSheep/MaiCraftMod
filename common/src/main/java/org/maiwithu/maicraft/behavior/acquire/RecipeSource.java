// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
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

    private final ReadsRecipes recipes;
    private final WorldMemory memory;
    private final ReadsFuels fuels;
    private final RecipeRuns runs;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ItemNeeds needs;

    public RecipeSource(ReadsRecipes recipes, WorldMemory memory, ReadsFuels fuels, RecipeRuns runs,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs) {
        this.recipes = recipes;
        this.memory = memory;
        this.fuels = fuels;
        this.runs = runs;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
        this.needs = needs;
    }

    @Override public String describe() {
        return "自己做";
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<RecipeView> candidates = recipes.recipesProducing(request.wanted());
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
            return new SourceQuote.Unavailable(describe(),
                    "有配方（" + recipe.id() + "），但没记得附近有" + workstationName(recipe.kind())
                            + "；用过一次才会记得它在哪");
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
        if (station.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assemble(recipe, station.get().position(),
                timesNeeded(recipe, request.count()), context));
    }

    // 备料的一整串：缺的原料逐项去弄，烧炼再备燃料，最后到设施上做。
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
