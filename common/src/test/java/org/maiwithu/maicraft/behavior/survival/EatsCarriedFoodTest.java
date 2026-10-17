// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;

/** 饿了挑身上哪件吃：正要用的那几件只在趁空当垫一口时绕开，伤得重、饿到不能疾跑就照吃。 */
class EatsCarriedFoodTest {

    /** 身上只有一块生羊肉，而且它是正要用的（上一个目标刚拿到、手上这件事要交）。 */
    private final BackpackView backpack = new BackpackView() {
        @Override public List<BackpackStack> stacks() {
            return List.of(new BackpackStack("minecraft:mutton", 1, 64, false, true, false, false));
        }

        @Override public int usedSlots() { return 1; }

        @Override public int totalSlots() { return 36; }
    };
    private final ReadsFoodValues foods = id -> id.equals("minecraft:mutton")
            ? Optional.of(new ReadsFoodValues.FoodValue(id, 2, 1.2f, 1.6f, false, List.of()))
            : Optional.empty();
    private final ItemsToKeep keeps = id -> id.equals("minecraft:mutton") ? 1 : 0;
    private final EatsCarriedFood moves = new EatsCarriedFood(backpack, foods, item -> Optional.empty(), null, keeps);

    @Test
    void 正要用的口粮_趁空当垫一口时不吃() {
        // 掉了几颗心、饱食度 14：只是趁空当垫一口，不动目标正要用的那块。
        assertFalse(moves.carriesBite(new HungerNeed.Facts(14, false, 0, true, false, 0)));
    }

    @Test
    void 正要用的口粮_伤得重或饿到不能疾跑时照吃() {
        // 10-15 直播 21:25:43：三颗心、饱食度 12，上一个目标拿到的那块生羊肉被留着，去烤熟肉又被怪打断。
        assertTrue(moves.carriesBite(new HungerNeed.Facts(12, false, 0, true, true, 0)), "血不到一半先顾身体");
        assertTrue(moves.carriesBite(new HungerNeed.Facts(HungerNeed.SPRINT_FLOOR, false, 0)), "饿到不能疾跑照吃");
    }
}
