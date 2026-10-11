// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.spi.RangedWeapon;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;

/** 联动远程武器的哪一发能打：口粮不打、分成几颗的只在近处打。 */
class ModRangedWeaponsTest {

    private static final Map<String, ReadsFoodValues.FoodValue> FOODS = Map.of(
            "minecraft:potato", new ReadsFoodValues.FoodValue("minecraft:potato", 1, 0.6f, 1.6f, false, List.of()),
            "minecraft:poisonous_potato", new ReadsFoodValues.FoodValue("minecraft:poisonous_potato", 2, 1.2f, 1.6f, false,
                    List.of(new ReadsFoodValues.FoodEffect("minecraft:poison", false))));

    private final ModRangedWeapons weapons = new ModRangedWeapons(List::of, itemId -> Optional.ofNullable(FOODS.get(itemId)));

    private static RangedWeapon.Shot shot(String ammo, int split) {
        return new RangedWeapon.Shot(ammo, 0, 0, 15, split,
                new RangedWeapon.Ballistics(2.0, 0.05, 0.99, true, Vec3.ZERO, Vec3.ZERO));
    }

    @Test
    void 留着吃的口粮不打_有害的食物与不是食物的照打() {
        assertFalse(weapons.usable(shot("minecraft:potato", 1), 5), "土豆吃了不伤身，是口粮");
        assertTrue(weapons.usable(shot("minecraft:poisonous_potato", 1), 5), "毒马铃薯不算口粮");
        assertTrue(weapons.usable(shot("minecraft:pumpkin", 1), 5), "南瓜块不是食物");
    }

    @Test
    void 分成几颗的只在近处打() {
        assertTrue(weapons.usable(shot("minecraft:pumpkin", 3), 6));
        assertFalse(weapons.usable(shot("minecraft:pumpkin", 3), 12));
    }

    @Test
    void 没有联动时什么都不认() {
        assertFalse(ModRangedWeapons.none().ration("minecraft:pumpkin"));
    }
}
