// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

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
        /** 没上弦的弩，身上有箭可以装：装一下再射。 */
        CROSSBOW,
        /** 联动模组的远程武器（土豆加农炮）：点一下就发，排在上了弦的弩之后、弓之前；挑法见 {@link ModRangedWeapons}。 */
        MOD_RANGED,
        /** 剑或斧：近战。对手举盾时优先斧。 */
        SWORD_OR_AXE,
        /** 空手。 */
        BARE_HANDS
    }

    /**
     * 挑选的结论：用哪一件（物品注册 ID）、属于哪一类、在背包哪一格（0~8 快捷栏，9~35 主背包，40 副手，空手为 -1）。
     */
    public record Picked(String itemId, Weapon weapon, int slot) {
        public Picked(String itemId, Weapon weapon) {
            this(itemId, weapon, -1);
        }
    }

    /**
     * 随身的一件东西：物品注册 ID、数量、弩是否已上弦、在背包哪一格，以及拿在主手时的攻击伤害与攻击速度
     * （按物品自己的攻击属性算，读不到时按空手的 1 点伤害、每秒 4 次）。
     */
    public record Carried(String item, int count, boolean crossbowCharged, int slot, double attackDamage,
                          double attackSpeed) {
        public Carried(String item, int count, boolean crossbowCharged) {
            this(item, count, crossbowCharged, -1, 1.0, 4.0);
        }
    }

    /**
     * 从随身的东西里挑武器。
     *
     * @param carried           背包与手里的东西
     * @param hasArrows         背包里有没有箭
     * @param opponentShielding 对手是否举着盾（斧优先）
     */
    public static Optional<Picked> pick(List<Carried> carried, boolean hasArrows, boolean opponentShielding) {
        return pickRanged(carried, hasArrows).or(() -> Optional.of(pickMelee(carried, opponentShielding)));
    }

    /**
     * 远程挑哪件：已上弦的弩（拿来就能射）、有箭的弓、有箭可装的弩；一样的快捷栏里的先（不用开背包）。都没有为空。
     */
    public static Optional<Picked> pickRanged(List<Carried> carried, boolean hasArrows) {
        Carried loaded = null;
        Carried bow = null;
        Carried crossbow = null;
        for (Carried stack : carried) {
            if (stack.count() <= 0) continue;
            if (isCrossbow(stack.item()) && stack.crossbowCharged()) loaded = handier(loaded, stack);
            else if (isBow(stack.item()) && hasArrows) bow = handier(bow, stack);
            else if (isCrossbow(stack.item()) && hasArrows) crossbow = handier(crossbow, stack);
        }
        if (loaded != null) return Optional.of(new Picked(loaded.item(), Weapon.LOADED_CROSSBOW, loaded.slot()));
        if (bow != null) return Optional.of(new Picked(bow.item(), Weapon.READY_BOW, bow.slot()));
        if (crossbow != null) return Optional.of(new Picked(crossbow.item(), Weapon.CROSSBOW, crossbow.slot()));
        return Optional.empty();
    }

    // 同一类里快捷栏的那件优先：拿起来只是按一下数字键。
    private static Carried handier(Carried current, Carried candidate) {
        if (current == null) return candidate;
        return inHotbar(candidate.slot()) && !inHotbar(current.slot()) ? candidate : current;
    }

    /**
     * 近战挑哪件：剑和斧里每秒伤害最高的（攻击伤害 × 攻击速度：满充能才挥，挥得快的剑常比斧划算）；
     * 一样好时快捷栏里的先（不用开背包）。对手举着盾时斧优先，斧是原版里能破盾的近战武器。都没有就空手。
     */
    public static Picked pickMelee(List<Carried> carried, boolean opponentShielding) {
        Carried best = null;
        for (Carried stack : carried) {
            if (stack.count() <= 0 || !(isSword(stack.item()) || isAxe(stack.item()))) continue;
            if (best == null || better(stack, best, opponentShielding)) best = stack;
        }
        return best == null ? new Picked(null, Weapon.BARE_HANDS, -1)
                : new Picked(best.item(), Weapon.SWORD_OR_AXE, best.slot());
    }

    // 举盾的对手面前斧压过剑；同类里比每秒伤害，再比是不是在快捷栏。
    private static boolean better(Carried candidate, Carried current, boolean opponentShielding) {
        if (opponentShielding && isAxe(candidate.item()) != isAxe(current.item())) return isAxe(candidate.item());
        double candidateRate = candidate.attackDamage() * candidate.attackSpeed();
        double currentRate = current.attackDamage() * current.attackSpeed();
        if (Math.abs(candidateRate - currentRate) > 1.0E-6) return candidateRate > currentRate;
        return inHotbar(candidate.slot()) && !inHotbar(current.slot());
    }

    /** 这一格是不是快捷栏（0~8）：快捷栏里的拿起来只是按一下数字键。 */
    public static boolean inHotbar(int slot) {
        return slot >= 0 && slot <= 8;
    }

    /** 简化武器分：进威胁评估用；弓弩不走近战路，分数里已把远程的优势算进去。 */
    public static int scoreOf(Weapon weapon) {
        return switch (weapon) {
            case LOADED_CROSSBOW -> 7;
            case READY_BOW, CROSSBOW, MOD_RANGED -> 6;
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
