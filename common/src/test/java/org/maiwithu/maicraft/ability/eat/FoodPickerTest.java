// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.eat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.game.player.ReadsFoodValues.FoodEffect;
import org.maiwithu.maicraft.game.player.ReadsFoodValues.FoodValue;

/** 挑哪种吃的：无效果优先、按补的多少排；带害的垫底；点名的只认点名的那种。 */
class FoodPickerTest {

    private static FoodValue food(String id, int nutrition, float saturation, FoodEffect... effects) {
        return new FoodValue(id, nutrition, saturation, 1.6f, false, List.of(effects));
    }

    private static FoodPicker.Carried carried(String id, int count, FoodValue value) {
        return new FoodPicker.Carried(id, count, value);
    }

    @Test
    void 没点名时无效果的食物优先_同档里补得多的在前() {
        List<FoodPicker.Carried> carried = List.of(
                carried("minecraft:golden_apple", 1,
                        food("minecraft:golden_apple", 4, 9.6f,
                                new FoodEffect("minecraft:regeneration", true))),
                carried("minecraft:bread", 3, food("minecraft:bread", 5, 6.0f)),
                carried("minecraft:cooked_beef", 2, food("minecraft:cooked_beef", 8, 12.8f)));
        assertEquals(Optional.of("minecraft:cooked_beef"), FoodPicker.autoPick(carried));
    }

    @Test
    void 只剩带效果的食物时有益的排在带害的前面_腐肉垫底() {
        List<FoodPicker.Carried> carried = List.of(
                carried("minecraft:rotten_flesh", 5, food("minecraft:rotten_flesh", 4, 0.8f,
                        new FoodEffect("minecraft:hunger", false))),
                carried("minecraft:golden_apple", 1, food("minecraft:golden_apple", 4, 9.6f,
                        new FoodEffect("minecraft:absorption", true))));
        assertEquals(Optional.of("minecraft:golden_apple"), FoodPicker.autoPick(carried));
    }

    @Test
    void 点名的在身上就选它_不在就选不出来() {
        ReadsItemTags tags = id -> Set.of();
        List<FoodPicker.Carried> carried = List.of(
                carried("minecraft:bread", 1, food("minecraft:bread", 5, 6.0f)));
        assertEquals(Optional.of("minecraft:bread"),
                FoodPicker.named(carried, "minecraft:bread", tags));
        assertEquals(Optional.empty(), FoodPicker.named(carried, "minecraft:cookie", tags));
    }

    @Test
    void 点名标签时按物品挂着的标签匹配() {
        ReadsItemTags tags =
                id -> "minecraft:bread".equals(id) ? Set.of("minecraft:foods") : Set.of();
        List<FoodPicker.Carried> carried = List.of(
                carried("minecraft:bread", 1, food("minecraft:bread", 5, 6.0f)));
        assertEquals(Optional.of("minecraft:bread"),
                FoodPicker.named(carried, "#minecraft:foods", tags));
        assertTrue(FoodPicker.autoPick(List.of()).isEmpty());
    }
}
