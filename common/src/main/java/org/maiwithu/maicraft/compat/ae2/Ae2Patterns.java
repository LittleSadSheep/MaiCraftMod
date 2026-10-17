// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 读 AE2 样板的模组读写接缝：一件编码过的样板主要产出什么，打开着的样板供应器界面里哪几格放样板。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，用 AE2 公开的样板解码与界面槽位用途。
 */
public interface Ae2Patterns {

    /** 读不到样板：没有读写端时用，认不出任何样板，样板供应器的界面也不认。 */
    Ae2Patterns NONE = new Ae2Patterns() {
        @Override public Set<Integer> openPatternSlots() {
            return Set.of();
        }

        @Override public Optional<String> patternOutput(ItemStack stack, ClientLevel level) {
            return Optional.empty();
        }
    };

    /** 此刻打开着的是样板供应器的界面时，放样板的那几格的格号；不是它的界面给空集。 */
    Set<Integer> openPatternSlots();

    /** 这件是编码过的样板时，它主要产出的物品 ID；不是样板、产出不是物品（流体）、解不开给空。 */
    Optional<String> patternOutput(ItemStack stack, ClientLevel level);
}
