// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 写错的注册 ID 找相近的名字：只比冒号后的名字，像到一定程度才给，标签只和标签比。 */
class SimilarIdsTest {

    @Test
    void 只差几个字母给出那个ID() {
        assertEquals(Optional.of("minecraft:diamond"),
                SimilarIds.closest(List.of("minecraft:dirt", "minecraft:diamond"), "minecraft:diamon"));
    }

    @Test
    void 互相包含算很像() {
        assertEquals(Optional.of("minecraft:woodland_mansion"),
                SimilarIds.closest(List.of("minecraft:village_plains", "minecraft:woodland_mansion"), "minecraft:mansion"));
    }

    @Test
    void 共有一个长词也给提示() {
        assertEquals(Optional.of("minecraft:jungle_pyramid"),
                SimilarIds.closest(List.of("minecraft:desert_pyramid", "minecraft:jungle_pyramid"), "minecraft:jungle_temple"));
    }

    @Test
    void 只写错命名空间时同名的排在包含关系前面() {
        // 两个候选都"像"：一个只是包含，一个名字一字不差；后者才是 LLM 想要的。
        assertEquals(Optional.of("create:wrench"),
                SimilarIds.closest(List.of("minecraft:wrench_holder", "create:wrench"), "minecraft:wrench"));
    }

    @Test
    void 一个都不像就不硬凑() {
        assertEquals(Optional.empty(),
                SimilarIds.closest(List.of("minecraft:diamond", "minecraft:stone"), "minecraft:qqqq"));
        assertEquals(Optional.empty(), SimilarIds.closest(List.of(), "minecraft:stone"));
    }

    @Test
    void 标签和标签比_建议补上井号() {
        assertEquals(Optional.of("#minecraft:logs"),
                SimilarIds.closest(List.of("minecraft:planks", "minecraft:logs"), "#minecraft:log"));
    }

    @Test
    void 统一说法有建议带后半句_没有就只说不存在() {
        assertEquals("物品 minecraft:diamon 不存在；你是不是要 minecraft:diamond",
                SimilarIds.missing("物品", "minecraft:diamon", Optional.of("minecraft:diamond")));
        assertEquals("群系 minecraft:qqqq 不存在", SimilarIds.missing("群系", "minecraft:qqqq", Optional.empty()));
    }
}
