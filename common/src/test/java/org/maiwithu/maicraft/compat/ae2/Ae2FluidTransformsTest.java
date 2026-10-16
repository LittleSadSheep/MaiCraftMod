// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/** AE2 的转化配方交给拿东西：途径 ae2_transform，触发的是第一样，缺东西的配方不交，读写端对不上就停用不给。 */
class Ae2FluidTransformsTest {

    private static final Ae2Transforms.Recipe FLUIX = new Ae2Transforms.Recipe("ae2:transform/fluix_crystals",
            List.of(List.of("ae2:charged_certus_quartz_crystal"), List.of("minecraft:redstone"), List.of("minecraft:quartz")),
            "ae2:fluix_crystal", 2, List.of("minecraft:water", "minecraft:flowing_water"));

    @Test
    void 联动入口交出途径为_ae2_transform_的配方表_触发的是第一样() {
        CompatRegistry registry = CompatRegistry.load(List.of(supported(level -> List.of(FLUIX))), loader());
        List<FluidTransforms> tables = registry.fluidTransforms();
        assertEquals(1, tables.size());
        assertEquals("ae2_transform", tables.getFirst().via().name());
        List<FluidTransform> recipes = tables.getFirst().recipes(null);
        assertEquals(1, recipes.size());
        assertEquals(0, recipes.getFirst().trigger());
        assertEquals("ae2:fluix_crystal", recipes.getFirst().resultId());
        assertEquals(2, recipes.getFirst().resultCount());
    }

    @Test
    void 缺料_缺流体_没产物的配方不交() {
        Ae2Transforms.Recipe noFluid = new Ae2Transforms.Recipe("a", FLUIX.ingredients(), "ae2:fluix_crystal", 2, List.of());
        Ae2Transforms.Recipe emptyIngredient = new Ae2Transforms.Recipe("b", List.of(List.of()), "ae2:fluix_crystal", 1,
                FLUIX.fluids());
        Ae2Transforms.Recipe noResult = new Ae2Transforms.Recipe("c", FLUIX.ingredients(), "", 0, FLUIX.fluids());
        assertEquals(List.of("ae2:transform/fluix_crystals"), Ae2FluidTransforms.translate(
                List.of(noFluid, emptyIngredient, noResult, FLUIX)).stream().map(FluidTransform::id).toList());
    }

    @Test
    void 读写端对不上_停用后一条配方都不给() {
        CompatRegistry registry = CompatRegistry.load(List.of(supported(level -> {
            throw new NoSuchMethodError("appeng.recipes.AERecipeTypes.TRANSFORM");
        })), loader());
        FluidTransforms table = registry.fluidTransforms().getFirst();
        assertTrue(table.recipes(null).isEmpty());
        assertTrue(table.recipes(null).isEmpty(), "停用以后照样给空，不再碰读写端");
        assertTrue(registry.modules().stream().noneMatch(CompatModule::active));
    }

    private static SupportedMod<CompatModule> supported(Ae2Transforms transforms) {
        return new SupportedMod<>(Ae2Compat.MOD_ID, "应用能源2", new VerifiedVersions("19.2.17", "19.3"),
                () -> new Ae2Compat(CraftOrderingTest.NO_TERMINALS, new FakeCraftPages(), new NoGrid(), Ae2Patterns.NONE,
                        transforms));
    }

    private static LoaderEnvironment loader() {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(Ae2Compat.MOD_ID); }
            @Override public Optional<String> modVersion(String modId) {
                return isModLoaded(modId) ? Optional.of("19.2.17") : Optional.empty();
            }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
    }
}
