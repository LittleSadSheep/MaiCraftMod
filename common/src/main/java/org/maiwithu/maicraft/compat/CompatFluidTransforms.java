// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;

/**
 * 联动模组交来的"扔进流体里会变"的配方表，登记表给它包的一层：模组停用后一条配方都不给（这条拿东西的途径就给不了），
 * 读配方时碰到模组接口对不上也在这里收住。途径名是登记时定下的，照常回答。
 */
final class CompatFluidTransforms implements FluidTransforms {

    private final CompatModule module;
    private final FluidTransforms transforms;

    CompatFluidTransforms(CompatModule module, FluidTransforms transforms) {
        this.module = Objects.requireNonNull(module, "module");
        this.transforms = Objects.requireNonNull(transforms, "transforms");
    }

    @Override public AcquireVia via() {
        return transforms.via();
    }

    @Override public List<FluidTransform> recipes(Level level) {
        if (!module.active()) return List.of();
        try {
            return transforms.recipes(level);
        } catch (ModApiMismatch broken) {
            // 已经停用，联动入口的日志里写过原因。
            return List.of();
        }
    }
}
