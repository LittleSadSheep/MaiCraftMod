// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 物品说明从注册表读：方块物品给出方块与方块状态，普通物品没有方块；空气与不存在的物品没有说明；
 * 不在世界里时悬停说明为空，不报错。
 */
class ClientItemDescriptionsTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final ClientItemDescriptions items = new ClientItemDescriptions(() -> null);

    @Test
    void 方块物品给出方块状态() {
        ReadsItemDescriptions.ItemDescription furnace = items.describe("minecraft:furnace").orElseThrow();

        assertEquals("minecraft:furnace", furnace.blockId());
        List<String> names = furnace.blockProperties().stream().map(ReadsItemDescriptions.BlockProperty::name).toList();
        assertTrue(names.containsAll(List.of("facing", "lit")), names.toString());
        ReadsItemDescriptions.BlockProperty lit = furnace.blockProperties().stream()
                .filter(property -> property.name().equals("lit")).findFirst().orElseThrow();
        assertEquals("false", lit.defaultValue());
        assertTrue(furnace.tooltip().isEmpty(), "不在世界里时悬停说明为空");
    }

    @Test
    void 普通物品没有方块_空气与不存在的物品没有说明() {
        assertNull(items.describe("minecraft:stick").orElseThrow().blockId());
        assertTrue(items.describe("minecraft:air").isEmpty());
        assertTrue(items.describe("minecraft:no_such_item").isEmpty());
    }

    @Test
    void 按ID的一段搜得到物品() {
        assertTrue(items.search(List.of("blast_furnace")).contains("minecraft:blast_furnace"));
        assertTrue(items.search(List.of("minecraft:air")).isEmpty());
    }
}
