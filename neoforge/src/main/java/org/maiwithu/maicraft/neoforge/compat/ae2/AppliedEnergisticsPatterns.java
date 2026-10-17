// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.PatternProviderMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.compat.ae2.Ae2Patterns;

/**
 * AE2 样板的读写端：样板主要产出什么用 AE2 公开的样板解码，样板供应器界面里哪几格放样板
 * 按 AE2 给界面槽位标的用途（编码样板格）认。
 */
public final class AppliedEnergisticsPatterns implements Ae2Patterns {

    // 打开着的是样板供应器的界面才认：它标成编码样板用途的那几格就是放样板的格子。
    @Override public Set<Integer> openPatternSlots() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof PatternProviderMenu menu)) return Set.of();
        Set<Integer> found = new LinkedHashSet<>();
        for (Slot slot : menu.getSlots(SlotSemantics.ENCODED_PATTERN)) found.add(slot.index);
        return Set.copyOf(found);
    }

    // 编码过的样板才解：主要产出是物品时给物品 ID，流体产出与解不开的不算。
    @Override public Optional<String> patternOutput(ItemStack stack, ClientLevel level) {
        if (stack.isEmpty() || level == null || !PatternDetailsHelper.isEncodedPattern(stack)) return Optional.empty();
        IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
        if (details == null) return Optional.empty();
        GenericStack output = details.getPrimaryOutput();
        if (output == null || !(output.what() instanceof AEItemKey item)) return Optional.empty();
        return Optional.of(BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
    }
}
