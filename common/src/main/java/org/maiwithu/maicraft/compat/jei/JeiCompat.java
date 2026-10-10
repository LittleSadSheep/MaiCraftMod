// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.jei;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * JEI 的联动入口：把 JEI 交给查配方当配方查看器。JEI 不导入别家的配方，EMI 也装着时配方查询先问 EMI。
 *
 * <p>JEI 的格子不带标签：一格能放好几样物品时，找一个装的物品和这一格候选完全相同的物品标签写成标签
 * （"木板"比列十几种木板好读）；只认完全相同的，找不到就照列全部候选。
 */
public final class JeiCompat extends CompatModule {

    public static final String MOD_ID = "jei";

    private final JeiReads reads;

    public JeiCompat(JeiReads reads) {
        super(MOD_ID, "JEI");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        registry.recipeViewer(this, new Viewer());
    }

    /** 一格的候选都是物品、不止一样时，找装的物品与候选完全相同的物品标签；找不到原样返回。 */
    static ShownIngredient withExactTag(ShownIngredient ingredient) {
        if (ingredient.tag() != null || ingredient.options().size() < 2) return ingredient;
        Set<String> wanted = new HashSet<>();
        for (ShownStack option : ingredient.options()) {
            if (option.kind() != ShownStack.Kind.ITEM) return ingredient;
            wanted.add(option.id());
        }
        Optional<TagKey<Item>> exact = BuiltInRegistries.ITEM.getTagNames().filter(tag -> {
            var contents = BuiltInRegistries.ITEM.getTag(tag).orElse(null);
            return contents != null && contents.size() == wanted.size() && contents.stream()
                    .allMatch(holder -> wanted.contains(BuiltInRegistries.ITEM.getKey(holder.value()).toString()));
        }).findFirst();
        return exact.map(tag -> new ShownIngredient(tag.location().toString(), ingredient.options(), ingredient.amount()))
                .orElse(ingredient);
    }

    private static List<ShownRecipe> withTags(List<ShownRecipe> recipes) {
        List<ShownRecipe> tagged = new ArrayList<>();
        for (ShownRecipe recipe : recipes) {
            tagged.add(new ShownRecipe(recipe.recipeId(), recipe.category(), recipe.categoryName(), recipe.workstations(),
                    recipe.inputs().stream().map(JeiCompat::withExactTag).toList(),
                    recipe.catalysts().stream().map(JeiCompat::withExactTag).toList(), recipe.outputs(), recipe.definition()));
        }
        return List.copyOf(tagged);
    }

    /** JEI 当配方查看器：碰 JEI 的每一下都经联动入口，接口对不上时联动停用。 */
    private final class Viewer implements RecipeViewer {
        @Override public String name() {
            return MOD_ID;
        }

        @Override public boolean importsOtherViewers() {
            return false;
        }

        @Override public Readiness readiness() {
            return call("看 JEI 的运行时交出来没有", reads::runtimeReady) ? Readiness.yes()
                    : Readiness.no("JEI 还没准备好（进世界、配方加载完之后才有）");
        }

        @Override public List<ShownRecipe> making(String itemId) {
            return withTags(call("查 JEI 里怎么做出它", () -> reads.making(itemId)));
        }

        @Override public List<ShownRecipe> using(String itemId) {
            return withTags(call("查 JEI 里拿它做什么", () -> reads.using(itemId)));
        }

        @Override public List<ShownRecipe> atWorkstation(String itemId) {
            return withTags(call("查 JEI 里它当工作站的配方", () -> reads.atWorkstation(itemId)));
        }
    }
}
