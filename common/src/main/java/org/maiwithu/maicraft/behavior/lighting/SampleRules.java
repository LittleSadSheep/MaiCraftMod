// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 照明的分类规则：一格方块是样本、还是敏感面。纯函数，只看方块状态本身。
 *
 * <p>样本是"要验收亮度的格"：能站住的可行走格，或（农田档）作物格与耕地上方的格。
 * 敏感面是保护事实：有方块实体、流体、作物与耕地、土径、火与营火与传送门、红石信号源、
 * 非原版方块——这些格不落灯、不改动；庄稼地不踩不压，灯插在田边或没种东西的地上。
 */
public final class SampleRules {

    private SampleRules() {}

    /** 样本种类：可行走格，作物格，耕地上方的格。 */
    public enum SampleKind { WALKABLE, CROP, FARMLAND }

    /** 这一格是不是敏感面：不落灯、不改动。岩浆归在流体里。 */
    public static boolean sensitive(BlockState state) {
        if (state.hasBlockEntity()) return true;
        if (!state.getFluidState().isEmpty()) return true;
        if (isCrop(state) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) return true;
        if (state.getBlock() instanceof BaseFireBlock || state.getBlock() instanceof CampfireBlock) return true;
        if (state.getBlock() instanceof NetherPortalBlock || state.is(Blocks.END_PORTAL)) return true;
        if (state.isSignalSource()) return true;
        // 联动模组的方块不认识就不动：认得出的容器与机器也不该被照明改掉。
        return foreign(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** 非原版方块：注册表里查不到的、或命名空间不是 minecraft 的，一律当敏感面。 */
    static boolean foreign(ResourceLocation id) {
        return id == null || !"minecraft".equals(id.getNamespace());
    }

    /** 这一格是庄稼（按原版规则要光照够 9 才长的那类）。 */
    public static boolean isCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock || state.getBlock() instanceof NetherWartBlock;
    }

    /** 身体能不能占这一格：空气，或可被替换的草丛这类（液体不算）。 */
    public static boolean passable(BlockState state) {
        if (state.isAir()) return true;
        return state.canBeReplaced() && state.getFluidState().isEmpty() && !sensitive(state);
    }
}
