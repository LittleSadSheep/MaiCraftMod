// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;

/**
 * 联动模组交来的配方查看器，登记表给它包的一层：模组停用后回答"用不了"并写明原因，配方查询就去问下一个查看器；
 * 读的时候碰到模组接口对不上，联动入口已经把它转成 ModApiMismatch 并停用模组，配方查询记一句原因换下一个。
 */
public final class CompatRecipeViewer implements RecipeViewer {

    private final CompatModule module;
    private final RecipeViewer viewer;

    public CompatRecipeViewer(CompatModule module, RecipeViewer viewer) {
        this.module = Objects.requireNonNull(module, "module");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
    }

    @Override public String name() {
        return viewer.name();
    }

    @Override public boolean importsOtherViewers() {
        return viewer.importsOtherViewers();
    }

    @Override public Readiness readiness() {
        if (!module.active()) {
            return Readiness.no(module.name() + "的联动已停用（" + module.disabledReason().orElse("") + "）");
        }
        try {
            return viewer.readiness();
        } catch (ModApiMismatch broken) {
            return Readiness.no(broken.getMessage());
        }
    }

    @Override public List<ShownRecipe> making(String itemId) {
        return module.active() ? viewer.making(itemId) : List.of();
    }

    @Override public List<ShownRecipe> using(String itemId) {
        return module.active() ? viewer.using(itemId) : List.of();
    }

    @Override public List<ShownRecipe> atWorkstation(String itemId) {
        return module.active() ? viewer.atWorkstation(itemId) : List.of();
    }
}
