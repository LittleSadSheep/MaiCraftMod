// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原版作物产出对照：四种庄稼各对上自己的田，别的东西不冒充庄稼。 */
class VanillaCropProductsTest {

    @Test
    void fourFieldCropsPointAtTheirPlants() {
        assertEquals(List.of("minecraft:wheat"), VanillaCropProducts.cropsProducing("minecraft:wheat"));
        assertEquals(List.of("minecraft:carrots"), VanillaCropProducts.cropsProducing("minecraft:carrot"));
        assertEquals(List.of("minecraft:potatoes"), VanillaCropProducts.cropsProducing("minecraft:potato"));
        assertEquals(List.of("minecraft:beetroots"), VanillaCropProducts.cropsProducing("minecraft:beetroot"));
    }

    @Test
    void unknownProductsSayThereIsNoCrop() {
        assertTrue(VanillaCropProducts.cropsProducing("minecraft:diamond").isEmpty());
    }
}
