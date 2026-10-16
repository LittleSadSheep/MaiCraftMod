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

    @Test
    void 注册ID读端按种类挑候选_标签说成某某标签() {
        ReadsRegisteredIds registered = new ReadsRegisteredIds() {
            @Override public List<String> ids(IdKind kind) {
                return switch (kind) {
                    case ITEM -> List.of("minecraft:oak_log", "minecraft:torch");
                    case BLOCK -> List.of("minecraft:coal_ore");
                    case ENTITY_TYPE -> List.of("minecraft:zombie");
                    case BIOME -> List.of("minecraft:beach", "minecraft:plains");
                    case STRUCTURE -> List.of("minecraft:village_plains");
                };
            }

            @Override public List<String> tags(IdKind kind) {
                return kind == IdKind.ITEM ? List.of("minecraft:logs") : List.of();
            }
        };
        assertEquals("物品 minecraft:oak_lg 不存在；你是不是要 minecraft:oak_log",
                registered.missing(IdKind.ITEM, "minecraft:oak_lg"));
        assertEquals("物品标签 #minecraft:log 不存在；你是不是要 #minecraft:logs",
                registered.missing(IdKind.ITEM, "#minecraft:log"));
        assertEquals("方块 minecraft:coal_oer 不存在；你是不是要 minecraft:coal_ore",
                registered.missing(IdKind.BLOCK, "minecraft:coal_oer"));
        assertEquals("实体 minecraft:zombi 不存在；你是不是要 minecraft:zombie",
                registered.missing(IdKind.ENTITY_TYPE, "minecraft:zombi"));
        assertEquals("群系 minecraft:beachh 不存在；你是不是要 minecraft:beach",
                registered.missing(IdKind.BIOME, "minecraft:beachh"));
        assertEquals("结构 minecraft:village 不存在；你是不是要 minecraft:village_plains",
                registered.missing(IdKind.STRUCTURE, "minecraft:village"));
    }
}
