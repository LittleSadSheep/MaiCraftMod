// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Create 置物台的原生取放入口只响应顶面；站位预检与实际瞄准必须遵循同一个操作面。 */
public final class CreateInteractionSurface {
    private CreateInteractionSurface() {}
    public static Direction requiredFace(BlockState state) {
        return requiredFace(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
    public static Direction requiredFace(ResourceLocation blockId) {
        // SharedDepotBlockMethods.onUse 对其他方向直接 PASS；不能把看见台座侧面当作已经能取放工件。
        return blockId.equals(ResourceLocation.fromNamespaceAndPath("create", "depot")) ? Direction.UP : null;
    }
}
