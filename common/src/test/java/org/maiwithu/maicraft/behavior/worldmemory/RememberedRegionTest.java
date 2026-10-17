// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 记住区域的存取：记下、覆盖、忘掉、重启还在，以及一个位置算不算在区域里的判断，走真实文档库。
 */
class RememberedRegionTest {

    private static final WorldPosition FARM_CENTER = new WorldPosition(200, 64, 120, "minecraft:overworld");

    @Test
    void 记下的区域重启后还在_按名字排序(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberRegion("河东的农场", FARM_CENTER, 16);
        memory.rememberRegion("磨坊", new WorldPosition(-50, 64, 40, "minecraft:overworld"), 8);
        var reopened = memory(temp);
        assertEquals(List.of("河东的农场", "磨坊"),
                reopened.regions().stream().map(RememberedRegion::name).toList());
        assertEquals(16, reopened.regions().get(0).radiusBlocks());
    }

    @Test
    void 同名区域用新范围覆盖(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberRegion("河东的农场", FARM_CENTER, 16);
        memory.rememberRegion("河东的农场", FARM_CENTER, 24);
        assertEquals(1, memory.regions().size());
        assertEquals(24, memory.regions().get(0).radiusBlocks());
    }

    @Test
    void 忘掉一块区域后查不到_别的区域不动(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberRegion("河东的农场", FARM_CENTER, 16);
        memory.rememberRegion("磨坊", new WorldPosition(-50, 64, 40, "minecraft:overworld"), 8);
        memory.forgetRegion("河东的农场");
        assertEquals(List.of("磨坊"), memory.regions().stream().map(RememberedRegion::name).toList());
    }

    @Test
    void 位置在半径内算在区域里_跨维度不算() {
        var region = new RememberedRegion("河东的农场", FARM_CENTER, 16);
        assertTrue(region.contains(new WorldPosition(210, 66, 125, "minecraft:overworld")));
        assertFalse(region.contains(new WorldPosition(240, 64, 120, "minecraft:overworld")));
        assertFalse(region.contains(new WorldPosition(200, 64, 120, "minecraft:the_nether")));
        // 维度没写的位置按角色当前维度算，与中心的空维度相配。
        assertTrue(region.contains(new WorldPosition(205, 64, 120, null)));
    }

    @Test
    void 早先没有区域段的老记忆文档当没有区域读() {
        // 只有一段记录与地点、没有区域段的老文档：读回来不报错，区域当空。
        var book = new MemoryCodec().decode(
                "{\"format\":1,\"records\":[],\"places\":{}}");
        assertTrue(book.regions().isEmpty());
    }

    private static WorldMemory memory(Path temp) {
        return new WorldMemory(documents(temp), "a".repeat(64));
    }

    private static DocumentStore documents(Path temp) {
        return new DocumentStore(temp.resolve("state.sqlite"));
    }
}
