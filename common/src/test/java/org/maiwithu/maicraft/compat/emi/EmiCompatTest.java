// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.emi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/**
 * EMI 当配方查看器：登记后配方查询先问它；还在加载时说还没好、换下一个；模组接口对不上时联动停用、换下一个。
 */
class EmiCompatTest {

    @Test
    void 登记后配方查询先问EMI() {
        FakeReads reads = new FakeReads();
        RecipeLookup lookup = new RecipeLookup(registry(reads).recipeViewers(), table());

        RecipeLookup.Answer answer = lookup.making("create:andesite_alloy");

        assertEquals("emi", answer.readFrom());
        assertEquals(1, answer.recipes().size());
    }

    @Test
    void 还在加载时换下一个并说明() {
        FakeReads reads = new FakeReads();
        reads.loaded = false;
        RecipeLookup lookup = new RecipeLookup(registry(reads).recipeViewers(), table());

        RecipeLookup.Answer answer = lookup.making("create:andesite_alloy");

        assertEquals("game", answer.readFrom());
        assertTrue(answer.notes().get(0).contains("EMI 还在加载配方"), answer.notes().toString());
    }

    @Test
    void 模组接口对不上时联动停用并换下一个() {
        FakeReads reads = new FakeReads();
        reads.broken = true;
        List<RecipeViewer> viewers = registry(reads).recipeViewers();
        RecipeLookup lookup = new RecipeLookup(viewers, table());

        RecipeLookup.Answer first = lookup.making("create:andesite_alloy");
        assertEquals("game", first.readFrom());
        assertFalse(viewers.get(0).readiness().ready(), "停用之后不再问");
        assertTrue(viewers.get(0).readiness().reason().contains("联动已停用"), viewers.get(0).readiness().reason());
    }

    private static CompatRegistry registry(FakeReads reads) {
        LoaderEnvironment loader = new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(EmiCompat.MOD_ID); }
            @Override public Optional<String> modVersion(String modId) { return Optional.of("1.1.24+1.21.1+neoforge"); }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
        return CompatRegistry.load(List.of(new SupportedMod<>(EmiCompat.MOD_ID, "EMI", new VerifiedVersions("1.1.24", "1.1.25"),
                () -> new EmiCompat(reads))), loader);
    }

    private static RecipeViewer table() {
        return new RecipeViewer() {
            @Override public String name() { return "game"; }
            @Override public boolean importsOtherViewers() { return false; }
            @Override public Readiness readiness() { return Readiness.yes(); }
            @Override public List<ShownRecipe> making(String itemId) { return List.of(); }
            @Override public List<ShownRecipe> using(String itemId) { return List.of(); }
            @Override public List<ShownRecipe> atWorkstation(String itemId) { return List.of(); }
        };
    }

    /** 替身 EMI：一条搅拌配方；可以设成还在加载、或一碰就接口对不上。 */
    private static final class FakeReads implements EmiReads {
        boolean loaded = true;
        boolean broken;

        @Override public boolean loaded() { return loaded; }

        @Override public List<ShownRecipe> making(String itemId) {
            if (broken) throw new NoSuchMethodError("getRecipesByOutput");
            ShownStack alloy = ShownStack.item(itemId, "安山合金", 1);
            return List.of(new ShownRecipe("create:mixing/andesite_alloy", "create:mixing", "搅拌", List.of(),
                    List.of(ShownIngredient.of(ShownStack.item("minecraft:andesite", "安山岩", 1))), List.of(), List.of(alloy), null));
        }

        @Override public List<ShownRecipe> using(String itemId) { return List.of(); }
        @Override public List<ShownRecipe> atWorkstation(String itemId) { return List.of(); }
    }
}
