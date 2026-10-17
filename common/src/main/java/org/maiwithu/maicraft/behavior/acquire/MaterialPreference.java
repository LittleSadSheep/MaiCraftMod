// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Comparator;
import java.util.Set;

/**
 * LLM 说的材料倾向：几种做法都行时，用到它点名的材料（物品或 # 标签）的那种排前面——它知道这个配方怎么做比较好，
 * 或者配方搜索卡在别处时指条路。只影响先后：不禁用别的做法，也不多开取材的来路。沿配方链往下传，备料时一样用。
 */
public final class MaterialPreference {

    private MaterialPreference() {}

    /** 排序：用到倾向材料的做法在前；没给倾向时不改先后。 */
    public static Comparator<WorkstationRecipe> first(Set<String> prefer, ReadsItemTags tags) {
        return Comparator.comparing((WorkstationRecipe recipe) -> !uses(recipe, prefer, tags));
    }

    /** 这种做法的原料里有没有一样是倾向的材料（点名的物品，或挂着点名标签的物品，或原料标签就是点名的那个）。 */
    public static boolean uses(WorkstationRecipe recipe, Set<String> prefer, ReadsItemTags tags) {
        if (prefer.isEmpty()) return false;
        // 可以互换的原料（任一张别的颜色的床）收的每一样都算：点名白床时，"别的颜色的任一张床"用得上它。
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            for (WantedItem item : ingredient.options()) {
                for (String wanted : prefer) {
                    if (matches(item, new WantedItem(wanted), tags)) return true;
                }
            }
        }
        return false;
    }

    // 原料与倾向对得上：同一样物品；倾向是标签、原料物品挂着它；原料是标签、倾向的物品挂着它或就是同一个标签。
    private static boolean matches(WantedItem ingredient, WantedItem prefer, ReadsItemTags tags) {
        if (!ingredient.isTag()) return prefer.matches(ingredient.itemId(), tags.tagsOf(ingredient.itemId()));
        if (prefer.isTag()) return prefer.tagId().equals(ingredient.tagId());
        return tags.tagsOf(prefer.itemId()).contains(ingredient.tagId());
    }
}
