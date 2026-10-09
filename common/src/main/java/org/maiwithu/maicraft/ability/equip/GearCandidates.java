// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsGearFit;

/**
 * 装备候选的判断：身上（主背包加副手）有哪些东西能放进目标栏位。
 *
 * <p>同一型多件算一种（同型的穿戴后果一样，自动选一件）；
 * 几种不同型的候选挑不出来时，由调用方向 LLM 提问选一种。
 * 纯函数：只读背包视图和游戏的栏位匹配规则，给结论。
 */
final class GearCandidates {

    private GearCandidates() {}

    /**
     * 身上能放进目标栏位的物品，同型去重，按身上先出现的顺序。
     */
    static List<String> fitting(BackpackView backpack, OffhandContents offhand,
            GearSlotName slot, ReadsGearFit fit) {
        Set<String> types = new LinkedHashSet<>();
        for (var stack : backpack.stacks()) {
            if (fit.fits(stack.itemId(), slot)) types.add(stack.itemId());
        }
        if (offhand != null) {
            offhand.heldInOffhand()
                    .filter(stack -> fit.fits(stack.itemId(), slot))
                    .map(stack -> stack.itemId())
                    .ifPresent(types::add);
        }
        return List.copyOf(types);
    }

    /**
     * 身上（主背包加副手）对得上 item 的物品 ID，同型去重，按身上先出现的顺序；
     * item 是标签（# 开头）时认挂着这个标签的任意一种。
     */
    static List<String> carriedMatching(BackpackView backpack, OffhandContents offhand, String item,
            ReadsItemTags tags) {
        Set<String> types = new LinkedHashSet<>();
        List<String> carried = new ArrayList<>();
        backpack.stacks().forEach(stack -> carried.add(stack.itemId()));
        if (offhand != null) {
            offhand.heldInOffhand().ifPresent(stack -> carried.add(stack.itemId()));
        }
        for (String itemId : carried) {
            boolean match = item.startsWith("#") ? tags.tagsOf(itemId).contains(item.substring(1)) : itemId.equals(item);
            if (match) types.add(itemId);
        }
        return List.copyOf(types);
    }
}
