// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.jei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/** JEI 的格子不带标签：只有候选和某个物品标签完全相同时才写成标签，对不上就照列全部候选。 */
class JeiCompatTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 对不上标签时照列全部候选() {
        ShownIngredient two = new ShownIngredient(null,
                List.of(ShownStack.item("minecraft:oak_planks", "橡木木板", 1), ShownStack.item("minecraft:stone", "石头", 1)), 1);

        ShownIngredient same = JeiCompat.withExactTag(two);

        assertNull(same.tag());
        assertEquals(2, same.options().size());
    }

    @Test
    void 只有一样或已经是标签的不动() {
        ShownIngredient one = ShownIngredient.of(ShownStack.item("minecraft:stick", "木棍", 2));
        assertEquals(one, JeiCompat.withExactTag(one));
        ShownIngredient tagged = new ShownIngredient("c:ingots/iron", List.of(ShownStack.item("minecraft:iron_ingot", "铁锭", 1)), 1);
        assertEquals(tagged, JeiCompat.withExactTag(tagged));
    }
}
