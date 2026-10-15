// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.game.player.GearSlotName;

/**
 * 寻死收起的护具记账：脱下的每件护甲记原件与原部位，等重生后（或要停下时）按原件穿回；
 * 脱不下、收不走、穿不回的按栏位记原因。不死图腾只收不记，不放回手上。
 *
 * <p>同一个栏位只记此刻的真相：再收一轮（暂停后恢复）时那个栏位的旧说法作废，穿回了就从"没穿回"里划掉。
 */
final class StowedGear {

    /** 收护具的先后：头到脚的护甲，再副手、主手上的不死图腾（免得图腾把这次死挡掉）。 */
    static final List<GearSlotName> ORDER = List.of(GearSlotName.HEAD, GearSlotName.CHEST, GearSlotName.LEGS,
            GearSlotName.FEET, GearSlotName.OFFHAND, GearSlotName.MAINHAND);

    /**
     * 一件脱下来、等着穿回的护甲。
     *
     * @param slot 原部位
     * @param worn 原件
     */
    record Piece(GearSlotName slot, SuicidePlayer.WornPiece worn) {
        String label() {
            return worn.itemId() + "（" + slot.paramName() + "）";
        }
    }

    private final List<Piece> toRestore = new ArrayList<>();
    private final Map<GearSlotName, String> stillWorn = new EnumMap<>(GearSlotName.class);
    private final Map<GearSlotName, String> totemsStillHeld = new EnumMap<>(GearSlotName.class);
    private final Map<GearSlotName, String> notRestored = new EnumMap<>(GearSlotName.class);

    /** 护甲栏位（头、胸、腿、脚）：要脱的；手上只收图腾。 */
    static boolean armor(GearSlotName slot) {
        return slot != GearSlotName.MAINHAND && slot != GearSlotName.OFFHAND;
    }

    /** 这件护甲脱下来了：记下原件等穿回。 */
    void stowed(Piece piece) {
        stillWorn.remove(piece.slot());
        notRestored.remove(piece.slot());
        toRestore.add(piece);
    }

    /** 这只手上的图腾收起来了。 */
    void totemStowed(GearSlotName hand) {
        totemsStillHeld.remove(hand);
    }

    /** 这一件没脱下来、没收走（背包满、绑定诅咒、没确认）：带着它死。 */
    void stillOn(GearSlotName slot, String itemId, String why) {
        String note = itemId + "（" + slot.paramName() + "）：" + why;
        if (armor(slot)) stillWorn.put(slot, note);
        else totemsStillHeld.put(slot, note);
    }

    /** 还等着穿回的护甲，按脱下的先后。 */
    List<Piece> toRestore() {
        return List.copyOf(toRestore);
    }

    boolean hasPiecesToRestore() {
        return !toRestore.isEmpty();
    }

    /** 这件穿回去了。 */
    void restored(Piece piece) {
        toRestore.remove(piece);
        notRestored.remove(piece.slot());
    }

    /** 这件没穿回（原件找不到、原部位有别的、没确认）：不再等它。 */
    void notRestored(Piece piece, String why) {
        toRestore.remove(piece);
        notRestored.put(piece.slot(), piece.label() + "：" + why);
    }

    /** 收尾时还没来得及穿回的（被新目标替换、程序出错）：照实列出还在背包里。 */
    void leftInBackpack(String why) {
        for (Piece piece : List.copyOf(toRestore)) notRestored(piece, why);
    }

    List<String> stillWorn() {
        return List.copyOf(stillWorn.values());
    }

    List<String> totemsStillHeld() {
        return List.copyOf(totemsStillHeld.values());
    }

    List<String> notRestored() {
        return List.copyOf(notRestored.values());
    }
}
