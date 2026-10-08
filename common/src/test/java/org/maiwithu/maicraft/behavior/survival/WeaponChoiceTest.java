// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

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
        // 没上弦的弩顶不了已上弦的：退到剑。
        assertEquals(WeaponChoice.Weapon.SWORD_OR_AXE,
                WeaponChoice.pick(List.of(BARE_CROSSBOW, SWORD), true, false).orElseThrow().weapon());
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
    void rangedWeaponsScoreHigherThanSword() {
        assertTrue(WeaponChoice.scoreOf(WeaponChoice.Weapon.LOADED_CROSSBOW)
                > WeaponChoice.scoreOf(WeaponChoice.Weapon.SWORD_OR_AXE));
        assertEquals(0, WeaponChoice.scoreOf(WeaponChoice.Weapon.BARE_HANDS));
    }
}
