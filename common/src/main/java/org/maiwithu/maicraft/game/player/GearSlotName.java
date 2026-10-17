// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 装备与卸下的目标栏位：主手、副手和四格护甲。
 *
 * <p>参数里用小写英文名（mainhand、offhand、head、chest、legs、feet），与玩家在游戏里的说法一致；
 * 整套卸下不在这里表达，由调用方逐格遍历护甲四格完成。
 */
public enum GearSlotName {
    MAINHAND("mainhand"),
    OFFHAND("offhand"),
    HEAD("head"),
    CHEST("chest"),
    LEGS("legs"),
    FEET("feet");

    private final String paramName;

    GearSlotName(String paramName) {
        this.paramName = paramName;
    }

    /** 参数里的写法，例如 mainhand。 */
    public String paramName() {
        return paramName;
    }

    /** 把参数取值读成栏位；写法不对时返回 null，由调用方决定怎么拒绝。 */
    public static GearSlotName fromParam(String value) {
        for (GearSlotName slot : values()) {
            if (slot.paramName.equals(value)) return slot;
        }
        return null;
    }
}
