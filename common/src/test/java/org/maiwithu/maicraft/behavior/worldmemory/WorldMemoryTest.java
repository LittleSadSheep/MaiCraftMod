// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 世界记忆的存取与查询：开过的箱子里有什么开了才算数、换世界互不串、
 * 记下的东西关掉再开还在，全部走临时目录的真实文档库，不碰游戏。
 */
class WorldMemoryTest {

    private static final int LIMIT = 4096;
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final WorldPosition CHEST = new WorldPosition(100, 64, -30, "minecraft:overworld");
    private static final WorldPosition FAR_CHEST =
            new WorldPosition(100_000, 64, -30, "minecraft:overworld");

    @Test
    void 亲眼看到的容器记得位置但不知道里面有什么(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberContainerSeen(CHEST, "minecraft:chest", NOW);
        var record = memory.recordAt(MemoryKind.CONTAINER, CHEST).orElseThrow();
        assertEquals(MemoryOrigin.SEEN, record.origin());
        assertEquals("minecraft:chest", record.blockType());
        // 没开过就是没开过：不凭外观猜里面有什么，也不当成空箱子。
        assertEquals(Optional.empty(), Optional.ofNullable(record.contents()));
    }

    @Test
    void 开过的容器记住里面有什么_确认是空的记成空(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:coal"), NOW);
        memory.rememberContainerOpened(FAR_CHEST, "minecraft:barrel", List.of(), NOW);
        assertEquals(List.of("minecraft:coal"),
                memory.recordAt(MemoryKind.CONTAINER, CHEST).orElseThrow().contents());
        // 空列表是"开过且确认是空的"，和 null 的"没开过"分得开。
        assertEquals(List.of(),
                memory.recordAt(MemoryKind.CONTAINER, FAR_CHEST).orElseThrow().contents());
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, FAR_CHEST).orElseThrow().openedBefore());
    }

    @Test
    void 同一个箱子先看到后打开_来源升到亲手用过(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberContainerSeen(CHEST, "minecraft:chest", NOW);
        memory.rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:bread"), NOW);
        var record = memory.recordAt(MemoryKind.CONTAINER, CHEST).orElseThrow();
        assertEquals(MemoryOrigin.USED, record.origin());
        assertEquals(List.of("minecraft:bread"), record.contents());
        // 同一个位置只留一条，不是看一次记一条。
        assertEquals(1, memory.allRecords().size());
    }

    @Test
    void 开过之后又路过看一眼_清单不丢(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        memory.rememberContainerSeen(CHEST, "minecraft:chest", NOW);
        assertEquals(List.of("minecraft:iron_ingot"),
                memory.recordAt(MemoryKind.CONTAINER, CHEST).orElseThrow().contents());
    }

    @Test
    void 到现场发现箱子没了_忘掉之后查不到(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberContainerOpened(CHEST, "minecraft:chest", List.of(), NOW);
        memory.forget(MemoryKind.CONTAINER, CHEST);
        assertEquals(Optional.empty(), memory.recordAt(MemoryKind.CONTAINER, CHEST));
    }

    @Test
    void 用过的工作站和产地线索分开记_互不覆盖(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberWorkstationUsed(CHEST, "minecraft:furnace", NOW);
        memory.rememberSite(CHEST, List.of("minecraft:coal_ore"), NOW);
        assertEquals(MemoryKind.WORKSTATION,
                memory.recordAt(MemoryKind.WORKSTATION, CHEST).orElseThrow().kind());
        assertEquals(List.of("minecraft:coal_ore"),
                memory.recordAt(MemoryKind.SITE, CHEST).orElseThrow().contents());
        assertEquals(2, memory.allRecords().size());
    }

    @Test
    void 按距离查同维度近处的_远的和别的维度不混进来(@TempDir Path temp) {
        var memory = memory(temp);
        memory.rememberContainerSeen(CHEST, "minecraft:chest", NOW);
        memory.rememberContainerSeen(FAR_CHEST, "minecraft:chest", NOW);
        memory.rememberContainerSeen(
                new WorldPosition(101, 64, -30, "minecraft:the_nether"), "minecraft:chest", NOW);
        var near = memory.recordsNear(new WorldPosition(102, 64, -30, "minecraft:overworld"), 16);
        assertEquals(1, near.size());
        assertEquals(CHEST, near.get(0).position());
    }

    @Test
    void 按距离查时近的在前_同远近新的在前(@TempDir Path temp) {
        var memory = memory(temp);
        WorldPosition near = new WorldPosition(0, 64, 0, "minecraft:overworld");
        WorldPosition far = new WorldPosition(10, 64, 0, "minecraft:overworld");
        memory.rememberContainerSeen(far, "minecraft:chest", NOW);
        memory.rememberContainerSeen(near, "minecraft:chest", NOW);
        var near3 = memory.recordsNear(new WorldPosition(0, 64, 0, "minecraft:overworld"), 64);
        assertEquals(near, near3.get(0).position());
        assertEquals(far, near3.get(1).position());
    }

    @Test
    void 记住地点并按名字查_同名覆盖(@TempDir Path temp) {
        var memory = memory(temp);
        WorldPosition oldBed = new WorldPosition(0, 64, 0, "minecraft:overworld");
        WorldPosition newBed = new WorldPosition(50, 64, 50, "minecraft:overworld");
        memory.remember("床", oldBed);
        assertEquals(Optional.of(oldBed), memory.place("床"));
        memory.remember("床", newBed);
        assertEquals(Optional.of(newBed), memory.place("床"));
    }

    @Test
    void 换世界身份_记忆互不串(@TempDir Path temp) {
        DocumentStore store = store(temp);
        var worldA = new WorldMemory(store, "a".repeat(64));
        var worldB = new WorldMemory(store, "b".repeat(64));
        worldA.rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:coal"), NOW);
        // 另一个世界没记过这只箱子，不能把 A 世界的记忆当自己的用。
        assertEquals(Optional.empty(), worldB.recordAt(MemoryKind.CONTAINER, CHEST));
        assertEquals(1, worldA.allRecords().size());
    }

    @Test
    void 关掉再开_记忆还在(@TempDir Path temp) {
        DocumentStore store = store(temp);
        new WorldMemory(store, "a".repeat(64))
                .rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:coal"), NOW);
        var reopened = new WorldMemory(store, "a".repeat(64));
        assertEquals(List.of("minecraft:coal"),
                reopened.recordAt(MemoryKind.CONTAINER, CHEST).orElseThrow().contents());
    }

    @Test
    void 从没记过任何东西时_查询给空不报错(@TempDir Path temp) {
        var memory = memory(temp);
        assertEquals(List.of(), memory.allRecords());
        assertEquals(Optional.empty(), memory.recordAt(MemoryKind.CONTAINER, CHEST));
        assertEquals(Optional.empty(), memory.place("家"));
    }

    private WorldMemory memory(Path temp) {
        return new WorldMemory(store(temp), "a".repeat(64));
    }

    private DocumentStore store(Path temp) {
        return new DocumentStore(temp.resolve("state.sqlite"));
    }
}
