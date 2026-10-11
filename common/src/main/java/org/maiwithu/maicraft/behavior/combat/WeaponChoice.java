// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.List;
import java.util.Optional;

/**
 * 武器挑选：从随身的东西里挑一件最顺手的，全仓只有这一份判断。
 *
 * <p>顺序：已上弦的弩、有箭的弓、近战武器，都没有就空手。对手举着盾时斧头优先——原版里斧能破盾。
 * 是什么武器按物品类与物品标签认（{@link Gear}），不按名字后缀猜：模组的剑、重锤、三叉戟都认得，
 * 名字里带 sword 的方块不会被当成剑。挑选只看背包里有什么，不替角色去买去造。
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
        /** 近战武器：剑、斧、重锤、三叉戟与模组的近战武器。对手举盾时优先斧。 */
        MELEE,
        /** 空手。 */
        BARE_HANDS
    }

    /** 一件东西是哪种兵器：按物品类与物品标签认。 */
    public enum Gear {
        /** 弓与模组的弓（远程武器里不是弩的）。 */
        BOW,
        /** 弩。 */
        CROSSBOW,
        /** 斧：近战，能破盾。 */
        AXE,
        /** 剑、重锤、三叉戟与模组的近战武器。 */
        BLADE,
        /** 不是兵器：镐铲锄这类工具、方块、食物。 */
        OTHER
    }

    /** 没读到攻击属性时按铁剑估的每秒伤害（6 × 1.6）。 */
    static final double TYPICAL_MELEE_RATE = 9.6;

    /**
     * 挑选的结论：用哪一件（物品注册 ID）、属于哪一类、在背包哪一格（0~8 快捷栏，9~35 主背包，40 副手，空手为 -1）、
     * 近战的每秒伤害（攻击伤害 × 攻击速度；远程与空手为 0）。
     */
    public record Picked(String itemId, Weapon weapon, int slot, double rate) {
        public Picked(String itemId, Weapon weapon, int slot) {
            this(itemId, weapon, slot, weapon == Weapon.MELEE ? TYPICAL_MELEE_RATE : 0);
        }

        public Picked(String itemId, Weapon weapon) {
            this(itemId, weapon, -1);
        }
    }

    /**
     * 随身的一件东西：物品注册 ID、数量、弩是否已上弦、在背包哪一格、拿在主手时的攻击伤害与攻击速度
     * （按物品自己的攻击属性算，读不到时按空手的 1 点伤害、每秒 4 次），以及它是哪种兵器。
     */
    public record Carried(String item, int count, boolean crossbowCharged, int slot, double attackDamage,
                          double attackSpeed, Gear gear) {
        /** 不是兵器的一件（方块、食物、工具）。 */
        public Carried(String item, int count) {
            this(item, count, false, -1, 1.0, 4.0, Gear.OTHER);
        }

        /** 每秒伤害：满充能才挥，挥得快的剑常比斧划算。 */
        public double rate() {
            return attackDamage * attackSpeed;
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
            if (stack.gear() == Gear.CROSSBOW && stack.crossbowCharged()) loaded = handier(loaded, stack);
            else if (stack.gear() == Gear.BOW && hasArrows) bow = handier(bow, stack);
            else if (stack.gear() == Gear.CROSSBOW && hasArrows) crossbow = handier(crossbow, stack);
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
     * 近战挑哪件：近战武器里每秒伤害最高的（攻击伤害 × 攻击速度：满充能才挥，挥得快的剑常比斧划算）；
     * 一样好时快捷栏里的先（不用开背包）。对手举着盾时斧优先，斧是原版里能破盾的近战武器。都没有就空手。
     * 镐铲锄是工具不是兵器，不拿来打（磨工具耐久还打不过空手太多）。
     */
    public static Picked pickMelee(List<Carried> carried, boolean opponentShielding) {
        Carried best = null;
        for (Carried stack : carried) {
            if (stack.count() <= 0 || stack.gear() != Gear.AXE && stack.gear() != Gear.BLADE) continue;
            if (best == null || better(stack, best, opponentShielding)) best = stack;
        }
        return best == null ? new Picked(null, Weapon.BARE_HANDS, -1, 0)
                : new Picked(best.item(), Weapon.MELEE, best.slot(), best.rate());
    }

    // 举盾的对手面前斧压过剑；同类里比每秒伤害，再比是不是在快捷栏。
    private static boolean better(Carried candidate, Carried current, boolean opponentShielding) {
        boolean candidateAxe = candidate.gear() == Gear.AXE;
        if (opponentShielding && candidateAxe != (current.gear() == Gear.AXE)) return candidateAxe;
        double candidateRate = candidate.rate();
        double currentRate = current.rate();
        if (Math.abs(candidateRate - currentRate) > 1.0E-6) return candidateRate > currentRate;
        return inHotbar(candidate.slot()) && !inHotbar(current.slot());
    }

    /** 这一格是不是快捷栏（0~8）：快捷栏里的拿起来只是按一下数字键。 */
    public static boolean inHotbar(int slot) {
        return slot >= 0 && slot <= 8;
    }

    /**
     * 武器分：进威胁评估用。近战按每秒伤害折算（铁剑约 5、木剑约 3、下界合金剑约 6），不再一把剑都算一样；
     * 弓弩不走近战路，分数里已把远程的优势算进去。
     */
    public static int scoreOf(Picked picked) {
        return switch (picked.weapon()) {
            case LOADED_CROSSBOW -> 7;
            case READY_BOW, CROSSBOW, MOD_RANGED -> 6;
            case MELEE -> (int) Math.max(1, Math.min(7, Math.round(picked.rate() / 2)));
            case BARE_HANDS -> 0;
        };
    }

}
