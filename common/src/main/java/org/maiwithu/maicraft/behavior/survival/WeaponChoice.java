// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 武器挑选：从随身的东西里挑一件最顺手的，全仓只有这一份判断。
 *
 * <p>顺序：已上弦的弩、有箭的弓、剑或斧，都没有就空手。对手举着盾时斧头优先——
 * 原版里斧能破盾；混在羊群里动手时避开剑的横扫，会误伤旁边不合要求的羊。
 * 挑选只看背包里有什么，不替角色去买去造。
 */
public final class WeaponChoice {

    /** 挑中的武器属于哪一类。 */
    public enum Weapon {
        /** 已上弦的弩：拿来就能射。 */
        LOADED_CROSSBOW,
        /** 弓（背包里有箭）。 */
        READY_BOW,
        /** 剑或斧：近战。对手举盾时优先斧。 */
        SWORD_OR_AXE,
        /** 空手。 */
        BARE_HANDS
    }

    /** 挑选的结论：用哪一件（物品注册 ID），属于哪一类。 */
    public record Picked(String itemId, Weapon weapon) {}

    /** 随身的一件东西：物品注册 ID 与数量；弩是否已上弦由读的一侧给。 */
    public record Carried(String item, int count, boolean crossbowCharged) {}

    /**
     * 从随身的东西里挑武器。
     *
     * @param carried           背包与手里的东西
     * @param hasArrows         背包里有没有箭
     * @param opponentShielding 对手是否举着盾（斧优先）
     */
    public static Optional<Picked> pick(List<Carried> carried, boolean hasArrows, boolean opponentShielding) {
        for (Carried stack : carried) {
            if (isCrossbow(stack.item()) && stack.crossbowCharged() && stack.count() > 0) {
                return Optional.of(new Picked(stack.item(), Weapon.LOADED_CROSSBOW));
            }
        }
        for (Carried stack : carried) {
            if (isBow(stack.item()) && hasArrows && stack.count() > 0) {
                return Optional.of(new Picked(stack.item(), Weapon.READY_BOW));
            }
        }
        // 对手举盾优先斧：斧是原版里能破盾的近战武器。
        if (opponentShielding) {
            for (Carried stack : carried) {
                if (isAxe(stack.item()) && stack.count() > 0) {
                    return Optional.of(new Picked(stack.item(), Weapon.SWORD_OR_AXE));
                }
            }
        }
        for (Carried stack : carried) {
            if ((isSword(stack.item()) || isAxe(stack.item())) && stack.count() > 0) {
                return Optional.of(new Picked(stack.item(), Weapon.SWORD_OR_AXE));
            }
        }
        return Optional.of(new Picked(null, Weapon.BARE_HANDS));
    }

    /** 简化武器分：进威胁评估用；弓弩不走近战路，分数里已把远程的优势算进去。 */
    public static int scoreOf(Weapon weapon) {
        return switch (weapon) {
            case LOADED_CROSSBOW -> 7;
            case READY_BOW -> 6;
            case SWORD_OR_AXE -> 5;
            case BARE_HANDS -> 0;
        };
    }

    private static boolean endsWith(String item, String suffix) {
        return item != null && item.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static boolean isCrossbow(String item) { return endsWith(item, ":crossbow"); }

    private static boolean isBow(String item) { return endsWith(item, ":bow"); }

    private static boolean isSword(String item) { return endsWith(item, "_sword"); }

    private static boolean isAxe(String item) { return endsWith(item, "_axe"); }
}
