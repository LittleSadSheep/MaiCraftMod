// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 一格收好几样的原料（给床换颜色要的"别的颜色的任一张床"）怎么数、缺了去弄哪一样：
 * 身上几样都算数；去弄时身上已有的先用，几样都有挑最多的；都没有就挑最省的——身上的料够现做的连现做一起比，
 * 其余只比现成的来路（箱子里有的、自己放下能收回的）；都问不出代价时挑现做缺料最少的，再一样按配方里的先后。
 * 不只认排第一的那样：要品红床时不为排第一的黑床一路去弄黑染料、墨囊。
 */
final class IngredientChoice {

    /** 现做这一样还缺几件原料（这个来源能做的做法里缺得最少的）；做不了为 Integer.MAX_VALUE。 */
    @FunctionalInterface
    interface MakingShortfall {
        int missing(ItemRequest request, SourceContext context);
    }

    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ItemNeeds needs;

    IngredientChoice(BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs) {
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = Objects.requireNonNull(tags, "tags");
        this.needs = Objects.requireNonNull(needs, "needs");
    }

    /** 身上有几件放得进这一格：可以互换的原料几样都算（身上那张白床就能拿去换颜色）。 */
    int carried(WorkstationRecipe.IngredientStack ingredient) {
        int total = 0;
        for (WantedItem option : ingredient.options()) {
            total += CarriedItems.matching(backpack, offhand, option, tags);
        }
        return total;
    }

    /**
     * 这一格的原料本身就满足要的东西：做它不会让要的东西变多（给床换颜色时要任一张床，"别的颜色的任一张床"
     * 就是拿床换床）。可以互换的原料每一样都看。
     */
    boolean satisfies(WorkstationRecipe.IngredientStack ingredient, WantedItem wanted) {
        for (WantedItem item : ingredient.options()) {
            if (!item.isTag()) {
                if (wanted.matches(item.itemId(), tags.tagsOf(item.itemId()))) return true;
            } else if (wanted.isTag() ? wanted.tagId().equals(item.tagId())
                    : tags.tagsOf(wanted.itemId()).contains(item.tagId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一格收的原料与要的东西是同一类东西（床与床、羊毛与羊毛）：染成品配方收的是"别的颜色"，
 * 不含要的那个颜色本身，satisfies 认不出，得靠共享的物品标签认同类。
     * 同类改造还缺料时不如直配（缺一张床 vs 缺三块羊毛，缺料数排序总偏向改造，一绕就绕到
     * "弄白床 → 弄白羊毛 → 弄白染料 → 杀骷髅"的深链），排序时把它排到后面。
     */
    boolean kindred(WorkstationRecipe.IngredientStack ingredient, WantedItem wanted) {
        Set<String> wantedTags = wanted.isTag() ? Set.of(wanted.tagId()) : tags.tagsOf(wanted.itemId());
        for (WantedItem option : ingredient.options()) {
            Set<String> optionTags = option.isTag() ? Set.of(option.tagId()) : tags.tagsOf(option.itemId());
            for (String tag : optionTags) {
                if (wantedTags.contains(tag)) return true;
            }
        }
        return false;
    }

    /** 这一格缺 needed 件时去弄哪一样；只收一样的原料就是它自己。 */
    WantedItem pick(WorkstationRecipe.IngredientStack ingredient, int needed, SourceContext context,
            MakingShortfall making) {
        List<WantedItem> options = ingredient.options();
        if (options.size() == 1) return options.getFirst();
        WantedItem most = null;
        int mostCarried = 0;
        for (WantedItem option : options) {
            int carried = CarriedItems.matching(backpack, offhand, option, tags);
            if (carried > mostCarried) {
                most = option;
                mostCarried = carried;
            }
        }
        if (most != null) return most;
        WantedItem cheapest = null;
        double cheapestCost = Double.MAX_VALUE;
        WantedItem easiest = options.getFirst();
        int fewestMissing = Integer.MAX_VALUE;
        for (WantedItem option : options) {
            ItemRequest asked = new ItemRequest(option, Math.max(1, needed), "挑一样可以互换的原料");
            int missing = making.missing(asked, context);
            // 手头的料够现做的连现做一起比价；缺料的只看现成的来路，不拿"做一张要先打三只羊"的床去比路程。
            Optional<Double> cost = needs.cheapestCost(asked, context.permissions(), missing == 0);
            if (cost.isPresent() && cost.get() < cheapestCost) {
                cheapest = option;
                cheapestCost = cost.get();
            }
            if (missing < fewestMissing) {
                easiest = option;
                fewestMissing = missing;
            }
        }
        return cheapest != null ? cheapest : easiest;
    }
}
