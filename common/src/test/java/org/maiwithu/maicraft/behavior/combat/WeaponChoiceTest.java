// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 武器挑选：已上弦弩最优先、有箭才有弓、举盾优先斧、什么都没有就空手。 */
class WeaponChoiceTest {

    private static final WeaponChoice.Carried SWORD = new WeaponChoice.Carried("minecraft:iron_sword", 1, false);
    private static final WeaponChoice.Carried AXE = new WeaponChoice.Carried("minecraft:iron_axe", 1, false);
    private static final WeaponChoice.Carried BOW = new WeaponChoice.Carried("minecraft:bow", 1, false);
    private static final WeaponChoice.Carried LOADED_CROSSBOW =
            new WeaponChoice.Carried("minecraft:crossbow", 1, true);
    private static final WeaponChoice.Carried BARE_CROSSBOW =
            new WeaponChoice.Carried("minecraft:crossbow", 1, false);

    @Test
    void loadedCrossbowBeatsEverything() {
        var picked = WeaponChoice.pick(List.of(SWORD, LOADED_CROSSBOW), true, false);
        assertEquals(WeaponChoice.Weapon.LOADED_CROSSBOW, picked.orElseThrow().weapon());
    }

    @Test
    void bowOnlyWithArrows() {
        assertEquals(WeaponChoice.Weapon.READY_BOW,
                WeaponChoice.pick(List.of(BOW, SWORD), true, false).orElseThrow().weapon());
        assertEquals(WeaponChoice.Weapon.SWORD_OR_AXE,
                WeaponChoice.pick(List.of(BOW, SWORD), false, false).orElseThrow().weapon());
        // 没上弦的弩：身上有箭就装上再射；没箭装不了，退到剑。
        assertEquals(WeaponChoice.Weapon.CROSSBOW,
                WeaponChoice.pick(List.of(BARE_CROSSBOW, SWORD), true, false).orElseThrow().weapon());
        assertEquals(WeaponChoice.Weapon.SWORD_OR_AXE,
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
                new WeaponChoice.Carried("minecraft:cobblestone", 32, false)), false, false);
        assertEquals(WeaponChoice.Weapon.BARE_HANDS, picked.orElseThrow().weapon());
        assertTrue(picked.orElseThrow().itemId() == null);
    }

    @Test
    void meleePicksTheHighestDamageRateAndPrefersTheHotbarOnTies() {
        // 铁剑 6 伤害 × 1.6 攻速 = 9.6，铁斧 9 × 0.9 = 8.1：平时拿剑；木剑最差。
        var ironSword = new WeaponChoice.Carried("minecraft:iron_sword", 1, false, 20, 6, 1.6);
        var ironAxe = new WeaponChoice.Carried("minecraft:iron_axe", 1, false, 2, 9, 0.9);
        var woodenSword = new WeaponChoice.Carried("minecraft:wooden_sword", 1, false, 0, 4, 1.6);
        var picked = WeaponChoice.pickMelee(List.of(woodenSword, ironAxe, ironSword), false);
        assertEquals("minecraft:iron_sword", picked.itemId());
        assertEquals(20, picked.slot());
        // 对手举盾：斧能破盾，斧优先。
        assertEquals("minecraft:iron_axe", WeaponChoice.pickMelee(List.of(ironSword, ironAxe), true).itemId());
        // 一样好的两把剑：快捷栏里那把先，不用开背包。
        var sameInHotbar = new WeaponChoice.Carried("minecraft:iron_sword", 1, false, 3, 6, 1.6);
        assertEquals(3, WeaponChoice.pickMelee(List.of(ironSword, sameInHotbar), false).slot());
        // 镐不是近战武器：只有镐就空手（交给手里现有的打）。
        var pickaxe = new WeaponChoice.Carried("minecraft:iron_pickaxe", 1, false, 0, 4, 1.2);
        assertEquals(WeaponChoice.Weapon.BARE_HANDS, WeaponChoice.pickMelee(List.of(pickaxe), false).weapon());
    }

    @Test
    void rangedWeaponsScoreHigherThanSword() {
        assertTrue(WeaponChoice.scoreOf(WeaponChoice.Weapon.LOADED_CROSSBOW)
                > WeaponChoice.scoreOf(WeaponChoice.Weapon.SWORD_OR_AXE));
        assertEquals(0, WeaponChoice.scoreOf(WeaponChoice.Weapon.BARE_HANDS));
    }
}
