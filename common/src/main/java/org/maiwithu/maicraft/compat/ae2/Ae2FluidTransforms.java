// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;

/**
 * AE2 的转化配方交给拿东西：途径 ae2_transform，往静水里扔几样料等它变（福鲁伊克斯水晶这类）。
 * 触发的是配方的第一样（AE2 只看第一样有没有泡够、身边凑没凑齐别的料）；料或流体是空的、产物是空的那几条不交。
 */
final class Ae2FluidTransforms implements FluidTransforms {

    /** 这条途径：往水里扔料等它变。 */
    static final AcquireVia VIA = new AcquireVia("ae2_transform",
            "往静水里扔几样料，等它变好再捡回来（应用能源2 的福鲁伊克斯水晶这类）");

    private final Ae2Compat compat;

    Ae2FluidTransforms(Ae2Compat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public List<FluidTransform> recipes(Level level) {
        return translate(compat.transformRecipes(level));
    }

    /** AE2 的配方换成拿东西认的写法，纯函数：触发的是第一样；缺料、缺流体、没产物的不交。 */
    static List<FluidTransform> translate(List<Ae2Transforms.Recipe> recipes) {
        List<FluidTransform> out = new ArrayList<>();
        for (Ae2Transforms.Recipe recipe : recipes) {
            if (recipe.ingredients().isEmpty() || recipe.ingredients().stream().anyMatch(List::isEmpty)
                    || recipe.fluids().isEmpty() || recipe.resultCount() < 1 || recipe.resultId().isEmpty()) {
                continue;
            }
            out.add(new FluidTransform(recipe.id(), recipe.ingredients(), 0, recipe.resultId(), recipe.resultCount(),
                    recipe.fluids()));
        }
        return out;
    }
}
