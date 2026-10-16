// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;

/** 往水里扔料挑哪条配方、扔几份、每样用哪种物品。 */
class FluidTransformPlanTest {

    private static final List<String> WATER = List.of("minecraft:water", "minecraft:flowing_water");
    private static final String CHARGED = "ae2:charged_certus_quartz_crystal";
    private static final FluidTransform THREE = new FluidTransform("ae2:transform/fluix_crystals",
            List.of(List.of(CHARGED), List.of("minecraft:redstone"), List.of("minecraft:quartz")), 0,
            "ae2:fluix_crystal", 2, WATER);
    private static final FluidTransform DUST = new FluidTransform("ae2:transform/fluix_crystal",
            List.of(List.of(CHARGED), List.of("ae2:fluix_dust")), 0, "ae2:fluix_crystal", 1, WATER);

    @Test
    void 缺料一样多时挑一份出得多的_份数向上取整() {
        FluidTransformPlan.Plan plan = FluidTransformPlan.choose(List.of(DUST, THREE), "ae2:fluix_crystal"::equals, 7,
                item -> 0).orElseThrow();
        assertEquals(THREE, plan.recipe(), "身上什么都没有时两条都缺料：先挑一份出两个的");
        assertEquals(4, plan.sets());
        assertEquals(8, plan.yield());
        assertEquals(CHARGED, plan.trigger());
        assertEquals(Map.of(CHARGED, 4, "minecraft:redstone", 4, "minecraft:quartz", 4), plan.needs());
        assertEquals(12, plan.missing());
    }

    @Test
    void 身上的料凑得齐那一条先() {
        Map<String, Integer> carried = Map.of(CHARGED, 3, "ae2:fluix_dust", 3);
        FluidTransformPlan.Plan plan = FluidTransformPlan.choose(List.of(THREE, DUST), "ae2:fluix_crystal"::equals, 3,
                item -> carried.getOrDefault(item, 0)).orElseThrow();
        assertEquals(DUST, plan.recipe(), "粉的那条身上齐了，红石那条还缺");
        assertEquals(0, plan.missing());
    }

    @Test
    void 一次最多六十四份_产物对不上的配方不挑() {
        FluidTransformPlan.Plan plan = FluidTransformPlan.choose(List.of(THREE), "ae2:fluix_crystal"::equals, 500,
                item -> 0).orElseThrow();
        assertEquals(64, plan.sets());
        assertTrue(FluidTransformPlan.choose(List.of(THREE), "minecraft:diamond"::equals, 1, item -> 0).isEmpty());
    }

    @Test
    void 一样料几种都行时用身上有的_都没有用第一种_同一种出现两次件数加倍() {
        FluidTransform either = new FluidTransform("test:either",
                List.of(List.of(CHARGED), List.of("minecraft:oak_log", "minecraft:spruce_log"), List.of(CHARGED)), 0,
                "test:out", 1, WATER);
        FluidTransformPlan.Plan carriedSpruce = FluidTransformPlan.choose(List.of(either), "test:out"::equals, 2,
                item -> item.equals("minecraft:spruce_log") ? 5 : 0).orElseThrow();
        assertEquals("minecraft:spruce_log", carriedSpruce.choices().get(1));
        assertEquals(4, carriedSpruce.needs().get(CHARGED), "触发的那样在配方里写了两次，每份两件");
        FluidTransformPlan.Plan none = FluidTransformPlan.choose(List.of(either), "test:out"::equals, 2,
                item -> 0).orElseThrow();
        assertEquals("minecraft:oak_log", none.choices().get(1));
    }
}
