// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 武器挑选：已上弦弩最优先、有箭才有弓、举盾优先斧、什么都没有就空手。 */
class WeaponChoiceTest {

    private static final WeaponChoice.Carried SWORD = blade("minecraft:iron_sword", 20, 6, 1.6);
    private static final WeaponChoice.Carried AXE = axe("minecraft:iron_axe", 2, 9, 0.9);
    private static final WeaponChoice.Carried BOW = new WeaponChoice.Carried("minecraft:bow", 1, false, -1, 1, 4, WeaponChoice.Gear.BOW);
    private static final WeaponChoice.Carried LOADED_CROSSBOW =
            new WeaponChoice.Carried("minecraft:crossbow", 1, true, -1, 1, 4, WeaponChoice.Gear.CROSSBOW);
    private static final WeaponChoice.Carried BARE_CROSSBOW =
            new WeaponChoice.Carried("minecraft:crossbow", 1, false, -1, 1, 4, WeaponChoice.Gear.CROSSBOW);

    @Test
    void loadedCrossbowBeatsEverything() {
        var picked = WeaponChoice.pick(List.of(SWORD, LOADED_CROSSBOW), true, false);
        assertEquals(WeaponChoice.Weapon.LOADED_CROSSBOW, picked.orElseThrow().weapon());
    }

    @Test
    void bowOnlyWithArrows() {
        assertEquals(WeaponChoice.Weapon.READY_BOW,
                WeaponChoice.pick(List.of(BOW, SWORD), true, false).orElseThrow().weapon());
        assertEquals(WeaponChoice.Weapon.MELEE,
                WeaponChoice.pick(List.of(BOW, SWORD), false, false).orElseThrow().weapon());
        // 没上弦的弩：身上有箭就装上再射；没箭装不了，退到剑。
        assertEquals(WeaponChoice.Weapon.CROSSBOW,
                WeaponChoice.pick(List.of(BARE_CROSSBOW, SWORD), true, false).orElseThrow().weapon());
        assertEquals(WeaponChoice.Weapon.MELEE,
                WeaponChoice.pick(List.of(BARE_CROSSBOW, SWORD), false, false).orElseThrow().weapon());
    }

    @Test
    void axeFirstWhenOpponentShields() {
        assertEquals("minecraft:iron_axe",
                WeaponChoice.pick(List.of(SWORD, AXE), false, true).orElseThrow().itemId());
    }

    @Test
    void bareHandsWhenNothingElse() {
        Optional<WeaponChoice.Picked> picked = WeaponChoice.pick(List.of(
                new WeaponChoice.Carried("minecraft:cobblestone", 32)), false, false);
        assertEquals(WeaponChoice.Weapon.BARE_HANDS, picked.orElseThrow().weapon());
        assertTrue(picked.orElseThrow().itemId() == null);
    }

    @Test
    void meleePicksTheHighestDamageRateAndPrefersTheHotbarOnTies() {
        // 铁剑 6 伤害 × 1.6 攻速 = 9.6，铁斧 9 × 0.9 = 8.1：平时拿剑；木剑最差。
        var ironSword = blade("minecraft:iron_sword", 20, 6, 1.6);
        var ironAxe = axe("minecraft:iron_axe", 2, 9, 0.9);
        var woodenSword = blade("minecraft:wooden_sword", 0, 4, 1.6);
        var picked = WeaponChoice.pickMelee(List.of(woodenSword, ironAxe, ironSword), false);
        assertEquals("minecraft:iron_sword", picked.itemId());
        assertEquals(20, picked.slot());
        // 对手举盾：斧能破盾，斧优先。
        assertEquals("minecraft:iron_axe", WeaponChoice.pickMelee(List.of(ironSword, ironAxe), true).itemId());
        // 一样好的两把剑：快捷栏里那把先，不用开背包。
        var sameInHotbar = blade("minecraft:iron_sword", 3, 6, 1.6);
        assertEquals(3, WeaponChoice.pickMelee(List.of(ironSword, sameInHotbar), false).slot());
        // 镐不是近战武器：只有镐就空手（交给手里现有的打）。
        var pickaxe = new WeaponChoice.Carried("minecraft:iron_pickaxe", 1, false, 0, 4, 1.2, WeaponChoice.Gear.OTHER);
        assertEquals(WeaponChoice.Weapon.BARE_HANDS, WeaponChoice.pickMelee(List.of(pickaxe), false).weapon());
    }

    @Test
    void modWeaponsAreRecognisedByWhatTheyAreNotByTheirName() {
        // 模组的剑名字不带 sword、重锤三叉戟也不是剑斧：按物品类与标签认作近战武器，照样挑每秒伤害最高的。
        var modBlade = blade("othermod:katana", 4, 7, 1.8);
        var mace = blade("minecraft:mace", 5, 6, 0.6);
        assertEquals("othermod:katana", WeaponChoice.pickMelee(List.of(mace, modBlade), false).itemId());
        // 名字里带 sword 的方块不是剑。
        var block = new WeaponChoice.Carried("othermod:sword_rack", 1);
        assertEquals(WeaponChoice.Weapon.BARE_HANDS, WeaponChoice.pickMelee(List.of(block), false).weapon());
    }

    @Test
    void weaponScoreFollowsTheDamageRate() {
        int wooden = WeaponChoice.scoreOf(WeaponChoice.pickMelee(List.of(blade("minecraft:wooden_sword", 0, 4, 1.6)), false));
        int iron = WeaponChoice.scoreOf(WeaponChoice.pickMelee(List.of(blade("minecraft:iron_sword", 0, 6, 1.6)), false));
        assertTrue(iron > wooden, "铁剑比木剑分高：" + iron + " / " + wooden);
        assertTrue(WeaponChoice.scoreOf(new WeaponChoice.Picked("minecraft:crossbow", WeaponChoice.Weapon.LOADED_CROSSBOW))
                > iron);
        assertEquals(0, WeaponChoice.scoreOf(new WeaponChoice.Picked(null, WeaponChoice.Weapon.BARE_HANDS)));
    }

    private static WeaponChoice.Carried blade(String id, int slot, double damage, double speed) {
        return new WeaponChoice.Carried(id, 1, false, slot, damage, speed, WeaponChoice.Gear.BLADE);
    }

    private static WeaponChoice.Carried axe(String id, int slot, double damage, double speed) {
        return new WeaponChoice.Carried(id, 1, false, slot, damage, speed, WeaponChoice.Gear.AXE);
    }
}
