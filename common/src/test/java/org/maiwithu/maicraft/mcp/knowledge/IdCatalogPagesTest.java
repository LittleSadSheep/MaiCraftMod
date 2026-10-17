// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;

/** 本实例的 ID 目录页：群系按命名空间分组列全，结构读不到时如实说明并列认得出的那些。 */
class IdCatalogPagesTest {

    /** 替身注册表：群系与结构名单按给的来，其余一律没有。 */
    private static ReadsRegisteredIds registered(List<String> biomes, List<String> biomeTags, List<String> structures) {
        return new ReadsRegisteredIds() {
            @Override public List<String> ids(IdKind kind) {
                return kind == IdKind.BIOME ? biomes : kind == IdKind.STRUCTURE ? structures : List.of();
            }

            @Override public List<String> tags(IdKind kind) {
                return kind == IdKind.BIOME ? biomeTags : List.of();
            }
        };
    }

    @Test
    void 群系目录按命名空间分组列全_原版在前_标签另起一节() {
        IdCatalogPages pages = new IdCatalogPages(registered(
                List.of("terralith:alpine_grove", "minecraft:plains", "aether:skyroot_meadow", "minecraft:beach"),
                List.of("minecraft:is_ocean", "terralith:is_alpine"), List.of()));

        String page = pages.read(IdCatalogPages.BIOMES).text();

        assertTrue(page.contains("本实例注册了 4 个群系，分在 3 个命名空间里"), page);
        assertTrue(page.contains("## minecraft（2 个）\nminecraft:beach、minecraft:plains\n"), page);
        assertTrue(page.indexOf("## minecraft") < page.indexOf("## aether")
                && page.indexOf("## aether") < page.indexOf("## terralith"), "原版排最前，其余按命名空间名排：" + page);
        assertTrue(page.contains("## 群系标签（2 个）"), page);
        assertTrue(page.contains("### minecraft（1 个）\n#minecraft:is_ocean\n"), "标签写成照抄就能用的 # 写法：" + page);
    }

    @Test
    void 末段名就能读到目录页() {
        KnowledgeLibrary library = new KnowledgeLibrary(new IdCatalogPages(registered(
                List.of("minecraft:beach"), List.of(), List.of())));

        KnowledgeDocument biomes = library.find("biomes").orElseThrow();
        assertEquals(IdCatalogPages.BIOMES, biomes.uri());
        assertEquals(IdCatalogPages.STRUCTURES, library.find("structures").orElseThrow().uri());
    }

    @Test
    void 结构注册表读不到时如实说明并列认得出的结构() {
        IdCatalogPages pages = new IdCatalogPages(registered(List.of("minecraft:beach"), List.of(), List.of()));

        String page = pages.read(IdCatalogPages.STRUCTURES).text();

        assertTrue(page.contains("结构注册表留在服务端"), page);
        assertTrue(page.contains("minecraft:village") && page.contains("minecraft:stronghold"), page);
        assertTrue(pages.status().contains("结构注册表客户端读不到"), pages.status());
    }

    @Test
    void 读得到结构注册表时列本实例全部结构() {
        IdCatalogPages pages = new IdCatalogPages(registered(List.of("minecraft:beach"), List.of(),
                List.of("minecraft:village_plains", "betterdungeons:spider_dungeon")));

        String page = pages.read(IdCatalogPages.STRUCTURES).text();

        assertTrue(page.contains("本实例注册了 2 种结构"), page);
        assertTrue(page.contains("## betterdungeons（1 个）\nbetterdungeons:spider_dungeon\n"), page);
        assertEquals("本实例的群系与结构目录：1 个群系，2 个结构", pages.status());
    }

    @Test
    void 不在世界里读不到群系就如实说() {
        IdCatalogPages pages = new IdCatalogPages(registered(List.of(), List.of(), List.of()));

        assertTrue(pages.read(IdCatalogPages.BIOMES).text().contains("要进了世界才读得到"));
        assertEquals("本实例的群系与结构目录：群系要进了世界才读得到", pages.status());
    }
}
