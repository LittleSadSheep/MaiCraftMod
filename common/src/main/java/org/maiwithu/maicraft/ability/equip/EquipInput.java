// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.List;
import java.util.Locale;

import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 穿卸装备的任务输入：穿还是卸、哪个栏位、穿哪件。栏位写 armor 表示整套护甲卸下，
 * 只配合卸下——穿戴永远一次一件。
 *
 * @param unequip true 是卸下，false 是穿上
 * @param slot    目标栏位；整套护甲卸下时为 null
 * @param itemId  要穿的物品 ID；卸下与候选自动选时不为空由任务决定，这里只在点名时带值
 */
record EquipInput(boolean unequip, GearSlotName slot, String itemId) implements TaskInput {

    /** 整套护甲的四格。 */
    static final List<GearSlotName> ARMOR_SLOTS =
            List.of(GearSlotName.HEAD, GearSlotName.CHEST, GearSlotName.LEGS, GearSlotName.FEET);

    EquipInput {
        if (slot == null && !unequip) throw new IllegalArgumentException("穿装备必须指明栏位");
    }

    /** 这次要动的栏位：单个栏位就是它自己，armor 是四格护甲。 */
    List<GearSlotName> slots() {
        if (slot != null) return List.of(slot);
        return ARMOR_SLOTS;
    }

    @Override
    public String describe() {
        String slotText = slot == null ? "整套护甲" : slot.paramName();
        String itemText = itemId == null ? "" : " " + itemId;
        return (unequip ? "卸下 " : "穿上 ") + slotText + itemText;
    }

    /** 把参数里的栏位取值读成栏位；armor 是整套护甲卸下的写法，不在这里展开。 */
    static GearSlotName parseSlot(String value) {
        if ("armor".equals(value)) return null;
        GearSlotName slot = GearSlotName.fromParam(value);
        if (slot == null) {
            throw new IllegalArgumentException("栏位取值不对：" + value.toLowerCase(Locale.ROOT));
        }
        return slot;
    }
}
