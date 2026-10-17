// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 配方查询挑来源的规矩：会导入别家配方的查看器先问，不能回答就换下一个并说明原因，都不行退到游戏配方表；
 * 一次只用一个来源，同一类别下同一条配方只出一次。
 */
class RecipeLookupTest {

    private static final ShownRecipe MIXING = recipe("create:mixing/andesite_alloy", "create:mixing");
    private static final ShownRecipe CRAFTING = recipe("minecraft:andesite_alloy", "minecraft:crafting");

    @Test
    void 两个查看器都能回答时问会导入别家配方的那个() {
        FakeViewer jei = new FakeViewer("jei", false, RecipeViewer.Readiness.yes(), List.of(CRAFTING));
        FakeViewer emi = new FakeViewer("emi", true, RecipeViewer.Readiness.yes(), List.of(MIXING, CRAFTING));
        RecipeLookup lookup = new RecipeLookup(List.of(jei, emi), gameTable(List.of()));

        RecipeLookup.Answer answer = lookup.making("create:andesite_alloy");

        assertEquals("emi", answer.readFrom(), "联动清单里 JEI 排在前面也先问 EMI");
        assertEquals(List.of(MIXING, CRAFTING), answer.recipes(), "只用一个来源，不把 JEI 的结果并进来");
        assertTrue(answer.notes().isEmpty());
        assertEquals(0, jei.asked);
    }

    @Test
    void 查看器还在加载时换下一个并写明原因() {
        FakeViewer emi = new FakeViewer("emi", true, RecipeViewer.Readiness.no("EMI 还在加载配方"), List.of(MIXING));
        FakeViewer jei = new FakeViewer("jei", false, RecipeViewer.Readiness.yes(), List.of(CRAFTING));
        RecipeLookup lookup = new RecipeLookup(List.of(emi, jei), gameTable(List.of()));

        RecipeLookup.Answer answer = lookup.making("create:andesite_alloy");

        assertEquals("jei", answer.readFrom());
        assertEquals(List.of(CRAFTING), answer.recipes());
        assertEquals(List.of("emi 这次没回答：EMI 还在加载配方"), answer.notes());
    }

    @Test
    void 查看器读配方出错时换下一个_不报内部错误() {
        RecipeViewer broken = new FakeViewer("emi", true, RecipeViewer.Readiness.yes(), List.of()) {
            @Override public List<ShownRecipe> making(String itemId) {
                throw new IllegalStateException("模组接口对不上");
            }
        };
        RecipeLookup lookup = new RecipeLookup(List.of(broken), gameTable(List.of(CRAFTING)));

        RecipeLookup.Answer answer = lookup.making("create:andesite_alloy");

        assertEquals("game", answer.readFrom());
        assertTrue(answer.notes().get(0).contains("模组接口对不上"));
        assertTrue(answer.notes().get(1).contains("配方查看器这次都没回答"), "退到游戏配方表时说清看不出在哪台机器上做");
    }

    @Test
    void 没装查看器时由游戏配方表回答并说清看不出机器() {
        RecipeLookup lookup = new RecipeLookup(List.of(), gameTable(List.of(CRAFTING)));

        RecipeLookup.Answer answer = lookup.making("create:andesite_alloy");

        assertEquals("game", answer.readFrom());
        assertEquals(List.of(CRAFTING), answer.recipes());
        assertEquals(1, answer.notes().size());
        assertTrue(answer.notes().get(0).startsWith("这个实例里没有配方查看器"));
    }

    @Test
    void 谁都不能回答时如实说没回答() {
        FakeViewer table = new FakeViewer("game", false, RecipeViewer.Readiness.no("角色不在世界里"), List.of());
        RecipeLookup lookup = new RecipeLookup(List.of(), table);

        RecipeLookup.Answer answer = lookup.using("minecraft:iron_ingot");

        assertFalse(answer.answered());
        assertEquals(List.of("game 这次没回答：角色不在世界里"), answer.notes());
    }

    @Test
    void 同一类别下同一条配方只出一次_没有ID的展示配方各算各的() {
        ShownRecipe shownOnly = recipe(null, "create:mixing");
        FakeViewer emi = new FakeViewer("emi", true, RecipeViewer.Readiness.yes(),
                List.of(MIXING, MIXING, shownOnly, shownOnly, recipe("create:mixing/andesite_alloy", "create:automatic_shapeless")));
        RecipeLookup lookup = new RecipeLookup(List.of(emi), gameTable(List.of()));

        assertEquals(4, lookup.making("create:andesite_alloy").recipes().size());
    }

    private static ShownRecipe recipe(String id, String category) {
        ShownStack alloy = ShownStack.item("create:andesite_alloy", "安山合金", 1);
        ShownStack andesite = ShownStack.item("minecraft:andesite", "安山岩", 1);
        return new ShownRecipe(id, category, null, List.of(), List.of(ShownIngredient.of(andesite)), List.of(),
                List.of(alloy), null);
    }

    private static FakeViewer gameTable(List<ShownRecipe> recipes) {
        return new FakeViewer("game", false, RecipeViewer.Readiness.yes(), recipes);
    }

    /** 替身查看器：按给定的现状回答，记下被问了几次。 */
    private static class FakeViewer implements RecipeViewer {
        private final String name;
        private final boolean importsOthers;
        private final Readiness readiness;
        private final List<ShownRecipe> recipes;
        int asked;

        FakeViewer(String name, boolean importsOthers, Readiness readiness, List<ShownRecipe> recipes) {
            this.name = name;
            this.importsOthers = importsOthers;
            this.readiness = readiness;
            this.recipes = recipes;
        }

        @Override public String name() { return name; }
        @Override public boolean importsOtherViewers() { return importsOthers; }
        @Override public Readiness readiness() { return readiness; }
        @Override public List<ShownRecipe> making(String itemId) { asked++; return recipes; }
        @Override public List<ShownRecipe> using(String itemId) { asked++; return recipes; }
        @Override public List<ShownRecipe> atWorkstation(String itemId) { asked++; return recipes; }
    }
}
