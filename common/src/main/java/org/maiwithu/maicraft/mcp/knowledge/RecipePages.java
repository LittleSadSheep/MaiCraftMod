// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * lookup(topic=recipe) 的正文：配方页（怎么做出它）与用途页（能拿它做什么、它当工作站能做什么）。
 *
 * <p>配方页一条不省，每条带原料、催化剂、产出与原始定义；用途页可能上百条，每条压成一行（配方 ID、产出、原料），
 * 不带原始定义，要看某一条的细节查它产出物的配方页。两种页都按配方类别分组，写明这一类在哪做。
 */
public final class RecipePages {
    private RecipePages() {}

    /** 配方页：能做出这件物品的全部配方。 */
    public static JsonObject making(String itemId, String name, RecipeLookup.Answer answer) {
        JsonObject page = header(itemId, name, answer);
        page.addProperty("recipe_count", answer.recipes().size());
        page.add("categories", categories(answer.recipes(), true));
        return page;
    }

    /**
     * 用途页：它当原料或催化剂的配方，与它当工作站的加工。两部分各自写从哪读的（多半是同一个查看器）。
     *
     * @param asIngredient 拿它当原料或催化剂的配方
     * @param asWorkstation 它当工作站的那几类配方
     */
    public static JsonObject uses(String itemId, String name, RecipeLookup.Answer asIngredient, RecipeLookup.Answer asWorkstation) {
        JsonObject page = header(itemId, name, asIngredient);
        page.add("as_ingredient", section(asIngredient));
        page.add("as_workstation", section(asWorkstation));
        return page;
    }

    private static JsonObject header(String itemId, String name, RecipeLookup.Answer answer) {
        JsonObject page = new JsonObject();
        page.addProperty("item", itemId);
        page.addProperty("name", name);
        page.addProperty("read_from", answer.readFrom());
        return page;
    }

    private static JsonObject section(RecipeLookup.Answer answer) {
        JsonObject section = new JsonObject();
        section.addProperty("read_from", answer.readFrom());
        section.addProperty("recipe_count", answer.recipes().size());
        section.add("categories", categories(answer.recipes(), false));
        return section;
    }

    // 按配方类别分组，组内照查看器给的顺序；每组写类别、名字、在哪做与条数。
    private static JsonArray categories(List<ShownRecipe> recipes, boolean full) {
        Map<String, List<ShownRecipe>> grouped = new LinkedHashMap<>();
        for (ShownRecipe recipe : recipes) {
            grouped.computeIfAbsent(recipe.category(), category -> new ArrayList<>()).add(recipe);
        }
        JsonArray categories = new JsonArray();
        for (List<ShownRecipe> group : grouped.values()) {
            ShownRecipe first = group.getFirst();
            JsonObject category = new JsonObject();
            category.addProperty("category", first.category());
            category.addProperty("category_name", first.categoryName());
            JsonArray workstations = new JsonArray();
            first.workstations().forEach(stack -> workstations.add(stack(stack, stack.amount())));
            category.add("workstations", workstations);
            category.addProperty("recipe_count", group.size());
            JsonArray rows = new JsonArray();
            for (ShownRecipe recipe : group) {
                if (full) rows.add(recipe(recipe));
                else rows.add(line(recipe));
            }
            category.add("recipes", rows);
            categories.add(category);
        }
        return categories;
    }

    // 配方页的一条：配方 ID（给不出就是 null，不编）、原料、催化剂（有才写）、产出、原始定义（有才写）。
    private static JsonObject recipe(ShownRecipe recipe) {
        JsonObject row = new JsonObject();
        row.addProperty("recipe", recipe.recipeId());
        row.add("inputs", ingredients(recipe.inputs()));
        if (!recipe.catalysts().isEmpty()) row.add("catalysts", ingredients(recipe.catalysts()));
        JsonArray outputs = new JsonArray();
        recipe.outputs().forEach(stack -> outputs.add(stack(stack, stack.amount())));
        row.add("outputs", outputs);
        JsonObject definition = recipe.definition();
        if (definition != null) row.add("definition", definition);
        return row;
    }

    // 用途页的一行：配方 ID：产出 ← 原料。
    private static String line(ShownRecipe recipe) {
        List<String> outputs = recipe.outputs().stream().map(RecipePages::words).toList();
        List<String> inputs = recipe.inputs().stream().map(RecipePages::words).toList();
        return (recipe.recipeId() == null ? "（没有配方 ID）" : recipe.recipeId()) + "：" + String.join("、", outputs)
                + " ← " + String.join("、", inputs);
    }

    private static String words(ShownStack stack) {
        String text = stack.name() + " ×" + stack.amount() + (stack.kind() == ShownStack.Kind.FLUID ? " 毫桶" : "");
        return stack.hasChance() ? text + "（" + Math.round(stack.chance() * 100) + "%）" : text;
    }

    private static String words(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            String example = ingredient.options().isEmpty() ? "" : ingredient.options().getFirst().name();
            return example + "（#" + ingredient.tag() + "）×" + ingredient.amount();
        }
        if (ingredient.options().size() == 1) {
            ShownStack only = ingredient.options().getFirst();
            return only.name() + " ×" + ingredient.amount() + (only.kind() == ShownStack.Kind.FLUID ? " 毫桶" : "");
        }
        return String.join(" 或 ", ingredient.options().stream().map(ShownStack::name).toList()) + " ×" + ingredient.amount();
    }

    private static JsonArray ingredients(List<ShownIngredient> ingredients) {
        JsonArray rows = new JsonArray();
        ingredients.forEach(ingredient -> rows.add(ingredient(ingredient)));
        return rows;
    }

    // 一格原料：是标签写标签与一个示例名；只能放一样就写那一样；能放好几样就列出来。
    private static JsonObject ingredient(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            JsonObject tag = new JsonObject();
            tag.addProperty("tag", ingredient.tag());
            if (!ingredient.options().isEmpty()) tag.addProperty("name", ingredient.options().getFirst().name());
            tag.addProperty("count", ingredient.amount());
            return tag;
        }
        if (ingredient.options().size() == 1) {
            return stack(ingredient.options().getFirst(), ingredient.amount());
        }
        JsonObject oneOf = new JsonObject();
        JsonArray options = new JsonArray();
        ingredient.options().forEach(stack -> options.add(stack(stack, stack.amount())));
        oneOf.add("one_of", options);
        oneOf.addProperty("count", ingredient.amount());
        return oneOf;
    }

    // 一样东西：物品写件数，流体写毫桶，别的照查看器给的数；查看器标了几率才写几率。
    private static JsonObject stack(ShownStack stack, long amount) {
        JsonObject row = new JsonObject();
        switch (stack.kind()) {
            case ITEM -> {
                row.addProperty("item", stack.id());
                row.addProperty("name", stack.name());
                row.addProperty("count", amount);
            }
            case FLUID -> {
                row.addProperty("fluid", stack.id());
                row.addProperty("name", stack.name());
                row.addProperty("amount_mb", amount);
            }
            case OTHER -> {
                row.addProperty("other", stack.id());
                row.addProperty("name", stack.name());
                row.addProperty("amount", amount);
            }
        }
        if (stack.hasChance()) row.addProperty("chance", stack.chance());
        return row;
    }
}
