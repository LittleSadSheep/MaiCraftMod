// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsGearFit;

/** 装备候选：身上能放进目标栏位的物品同型去重，几种不同型的才需要问。 */
class GearCandidatesTest {

    /** 栏位匹配替身：按物品名的尾词回答——helmet 进 head、chestplate 进 chest。 */
    private static final ReadsGearFit FIT = (itemId, slot) -> switch (slot) {
        case HEAD -> itemId.endsWith("helmet");
        case CHEST -> itemId.endsWith("chestplate");
        case LEGS -> itemId.endsWith("leggings");
        case FEET -> itemId.endsWith("boots");
        default -> false;
    };

    private static BackpackStack stack(String itemId) {
        return new BackpackStack(itemId, 1, 64, true, false, false, false);
    }

    /** 背包替身：固定的几格。 */
    private record FixedBackpack(List<BackpackStack> stacks) implements BackpackView {
        private FixedBackpack(BackpackStack... stacks) { this(List.of(stacks)); }
        @Override public int usedSlots() { return stacks.size(); }
        @Override public int totalSlots() { return 36; }
    }

    /** 副手替身：拿着给的一格，或空手。 */
    private record FixedOffhand(Optional<BackpackStack> held) implements OffhandContents {
        private FixedOffhand(BackpackStack stack) { this(Optional.of(stack)); }
        @Override public Optional<BackpackStack> heldInOffhand() { return held; }
    }

    @Test
    void 同型多件去重_不同型都列出来() {
        FixedBackpack backpack = new FixedBackpack(
                stack("minecraft:iron_helmet"),
                stack("minecraft:iron_helmet"),
                stack("minecraft:diamond_helmet"));
        List<String> candidates = GearCandidates.fitting(backpack, new FixedOffhand(Optional.empty()),
                GearSlotName.HEAD, FIT);
        assertEquals(List.of("minecraft:iron_helmet", "minecraft:diamond_helmet"), candidates);
    }

    @Test
    void 不合栏位的不进候选_副手也算来源() {
        FixedBackpack backpack = new FixedBackpack(
                stack("minecraft:iron_chestplate"));
        FixedOffhand offhand = new FixedOffhand(stack("minecraft:iron_helmet"));
        List<String> candidates = GearCandidates.fitting(backpack, offhand, GearSlotName.HEAD, FIT);
        assertEquals(List.of("minecraft:iron_helmet"), candidates);
    }
}
