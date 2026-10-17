// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 物品资料页是总入口：按注册 ID 读，写名字、方块状态、按住 Shift 的用法、配方条数与去处、
 * 别的来源里跟它有关的条目与各来源现状；查不到配方、没登记来源时如实说。
 */
class ItemPagesTest {

    private static final String MIXER = "create:mechanical_mixer";

    @Test
    void 按注册ID读到物品资料页() {
        KnowledgeLibrary library = library(new FakeViewer(RecipeViewer.Readiness.yes()), List.of(new PonderLike()));

        KnowledgeDocument page = library.find(MIXER).orElseThrow();

        assertEquals(ItemPages.uri(MIXER), page.uri());
        String text = page.text();
        assertTrue(text.startsWith("# 动力搅拌器（create:mechanical_mixer）"), text);
        assertTrue(text.contains("方块状态：facing（north / south，默认 north）"), text);
        assertTrue(text.contains("按住 Shift 看到的用法"), text);
        assertTrue(text.contains("  - 有动力时 → 搅拌下方工作盆里的东西"), text);
        assertTrue(text.contains("能做出它的配方 1 条（读自 emi）"), text);
        assertTrue(text.contains("它是 \"搅拌\" 这几类配方的工作站，共 1 条"), text);
        assertTrue(text.contains("uses=true"), text);
        assertTrue(text.contains("动力搅拌器 · 场景 1：maicraft://knowledge/ponder/create/mechanical_mixer/1"), text);
        assertTrue(text.contains("  - 思索：可用，1 个场景"), text);
    }

    @Test
    void 注册表里没有的物品读不到() {
        KnowledgeLibrary library = library(new FakeViewer(RecipeViewer.Readiness.yes()), List.of());

        assertTrue(library.find("create:no_such_thing").isEmpty());
    }

    @Test
    void 按中文名搜得到物品资料页() {
        KnowledgeLibrary library = library(new FakeViewer(RecipeViewer.Readiness.yes()), List.of());

        List<String> found = library.matching("搅拌器").stream().map(KnowledgeDocument.Entry::uri).toList();

        assertEquals(List.of(ItemPages.uri(MIXER)), found);
    }

    @Test
    void 查不到配方与没登记来源时如实说() {
        KnowledgeLibrary library = library(new FakeViewer(RecipeViewer.Readiness.no("角色不在世界里")), List.of());

        String text = library.find(MIXER).orElseThrow().text();

        assertTrue(text.contains("这次查不到：game 这次没回答：角色不在世界里"), text);
        assertTrue(text.contains("别的资料来源里没有跟它有关的条目"), text);
        assertTrue(text.contains("这个实例没有登记模组的资料来源"), text);
    }

    private static KnowledgeLibrary library(RecipeViewer gameTable, List<KnowledgeSource> others) {
        ItemPages pages = new ItemPages(new FakeItems(), new RecipeLookup(List.of(), gameTable), others);
        List<KnowledgeSource> sources = new ArrayList<>();
        sources.add(pages);
        sources.addAll(others);
        return new KnowledgeLibrary(sources);
    }

    /** 替身物品表：只认得动力搅拌器。 */
    private static final class FakeItems implements ReadsItemDescriptions {
        @Override public Optional<ItemDescription> describe(String itemId) {
            if (!itemId.equals(MIXER)) return Optional.empty();
            return Optional.of(new ItemDescription(MIXER, "动力搅拌器", MIXER,
                    List.of(new BlockProperty("facing", "north", List.of("north", "south"))),
                    List.of("按住 [Shift] 查看摘要"), "把下方工作盆里的东西搅拌成别的东西",
                    List.of(new UsageLine("有动力时", "搅拌下方工作盆里的东西")), List.of()));
        }

        @Override public List<String> search(List<String> terms) {
            return terms.stream().allMatch((MIXER + " 动力搅拌器")::contains) ? List.of(MIXER) : List.of();
        }

        @Override public String nameOf(String itemId) {
            return itemId.equals(MIXER) ? "动力搅拌器" : itemId;
        }
    }

    /** 替身配方查看器：做出它一条、它当工作站的"搅拌"一条。 */
    private static final class FakeViewer implements RecipeViewer {
        private final Readiness readiness;

        FakeViewer(Readiness readiness) {
            this.readiness = readiness;
        }

        @Override public String name() { return readiness.ready() ? "emi" : "game"; }
        @Override public boolean importsOtherViewers() { return false; }
        @Override public Readiness readiness() { return readiness; }
        @Override public List<ShownRecipe> making(String itemId) { return List.of(recipe("minecraft:crafting", "合成")); }
        @Override public List<ShownRecipe> using(String itemId) { return List.of(); }
        @Override public List<ShownRecipe> atWorkstation(String itemId) { return List.of(recipe("create:mixing", "搅拌")); }

        private static ShownRecipe recipe(String category, String name) {
            ShownStack alloy = ShownStack.item("create:andesite_alloy", "安山合金", 1);
            return new ShownRecipe(category + "/x", category, name, List.of(), List.of(ShownIngredient.of(alloy)), List.of(),
                    List.of(ShownStack.item(MIXER, "动力搅拌器", 1)), null);
        }
    }

    /** 像思索那样的来源：跟动力搅拌器有关的一个场景。 */
    private static final class PonderLike implements KnowledgeSource {
        @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
        @Override public KnowledgeDocument read(String uri) { return null; }
        @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
            return registryId.equals(MIXER)
                    ? List.of(new KnowledgeDocument.Entry("maicraft://knowledge/ponder/create/mechanical_mixer/1", "scene",
                            "动力搅拌器 · 场景 1", "思索场景", ""))
                    : List.of();
        }
        @Override public String status() { return "思索：可用，1 个场景"; }
    }
}
