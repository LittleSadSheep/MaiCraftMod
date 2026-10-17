// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.emi;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * EMI 的联动入口：把 EMI 交给查配方当配方查看器。EMI 会导入 JEI 插件的配方类别，所以它报"会把别的查看器的配方一起导进来"，
 * 配方查询先问它；EMI 还在加载时回答还没好，配方查询就去问下一个。
 */
public final class EmiCompat extends CompatModule {

    public static final String MOD_ID = "emi";

    private final EmiReads reads;

    public EmiCompat(EmiReads reads) {
        super(MOD_ID, "EMI");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        registry.recipeViewer(this, new Viewer());
    }

    /** EMI 当配方查看器：碰 EMI 的每一下都经联动入口，接口对不上时联动停用。 */
    private final class Viewer implements RecipeViewer {
        @Override public String name() {
            return MOD_ID;
        }

        @Override public boolean importsOtherViewers() {
            return true;
        }

        @Override public Readiness readiness() {
            return call("看 EMI 加载完没有", reads::loaded) ? Readiness.yes()
                    : Readiness.no("EMI 还在加载配方（进世界后要几秒）");
        }

        @Override public List<ShownRecipe> making(String itemId) {
            return call("查 EMI 里怎么做出它", () -> reads.making(itemId));
        }

        @Override public List<ShownRecipe> using(String itemId) {
            return call("查 EMI 里拿它做什么", () -> reads.using(itemId));
        }

        @Override public List<ShownRecipe> atWorkstation(String itemId) {
            return call("查 EMI 里它当工作站的配方", () -> reads.atWorkstation(itemId));
        }
    }
}
