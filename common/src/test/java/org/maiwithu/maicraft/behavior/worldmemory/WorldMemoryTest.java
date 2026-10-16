// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void 看见时没写维度_开箱后写了维度_说的是同一只箱子(@TempDir Path temp) {
        // 实机：感知记的箱子位置不带维度，开箱记的带维度，记成了两只；按角色位置（带维度）查附近只找得到
        // 没开过的那条，拿东西每次都绕去翻同一只箱子。
        var memory = memory(temp);
        memory.containerSeen(WorldPosition.here(100, 64, -30), "minecraft:chest", NOW);
        memory.rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:oak_log"), NOW.plusSeconds(5));
        var near = memory.recordsNear(new WorldPosition(98, 64, -30, "minecraft:overworld"), 8).stream()
                .filter(record -> record.kind() == MemoryKind.CONTAINER).toList();
        assertEquals(1, near.size(), "合成一条");
        assertTrue(near.getFirst().openedBefore(), "记的是开过的样子");
        assertEquals("minecraft:overworld", near.getFirst().position().dimension(), "留下写了维度的说法");
    }

    @Test
    void 感知写来的看见记录与直接写入走同一条规矩(@TempDir Path temp) {
        var memory = memory(temp);
        // 感知只报"亲眼看到"：工作台看见就是看见，来源不冒充亲手用过。
        memory.workstationSeen(CHEST, "minecraft:crafting_table", NOW);
        memory.containerSeen(FAR_CHEST, "minecraft:barrel", NOW);
        assertEquals(MemoryOrigin.SEEN,
                memory.recordAt(MemoryKind.WORKSTATION, CHEST).orElseThrow().origin());
        assertEquals(MemoryOrigin.SEEN,
                memory.recordAt(MemoryKind.CONTAINER, FAR_CHEST).orElseThrow().origin());
        // 同一个位置看见两次，合并成一条而不是记两条。
        memory.containerSeen(FAR_CHEST, "minecraft:barrel", NOW);
        assertEquals(1, memory.recordsNear(FAR_CHEST, 1).size());
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
    void 没记过的地名_找同名的建筑档案_记过的地点优先(@TempDir Path temp) {
        // 盖完自动记一条"这一栋在哪、叫什么"：按名字指过去不用另外 remember；同名的记过地点在就以地点为准。
        var memory = memory(temp);
        WorldPosition anchor = new WorldPosition(10, 64, 10, "minecraft:overworld");
        memory.rememberBuilding(anchor, "小屋", NOW);
        assertEquals(Optional.of(anchor), memory.place("小屋"));
        assertEquals(Optional.empty(), memory.place("大屋"));
        WorldPosition door = new WorldPosition(12, 64, 9, "minecraft:overworld");
        memory.remember("小屋", door);
        assertEquals(Optional.of(door), memory.place("小屋"));
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
        var memory = new WorldMemory(store, "a".repeat(64));
        memory.rememberContainerOpened(CHEST, "minecraft:chest", List.of("minecraft:coal"), NOW);
        // 修改先记在内存里，离开世界前存盘；存过盘再开就还在。
        memory.flush();
        var reopened = new WorldMemory(store, "a".repeat(64));
        assertEquals(List.of("minecraft:coal"),
                reopened.recordAt(MemoryKind.CONTAINER, CHEST).orElseThrow().contents());
    }

    @Test
    void 边走边看同一片树林_只记一条产地线索(@TempDir Path temp) {
        var memory = memory(temp);
        // 角色在一片树林里走动，感知每次刷新都把看到的树林中心记一笔，中心每次挪一两格。
        for (int step = 0; step < 50; step++) {
            memory.siteSeen(new WorldPosition(5 + step % 7, 64, 3 + step % 5, "minecraft:overworld"),
                    List.of("木头"), NOW.plusSeconds(step));
        }
        // 走到另一个空间格里看到的树林是另一片。
        memory.siteSeen(new WorldPosition(200, 64, 3, "minecraft:overworld"), List.of("木头"), NOW);
        List<MemoryRecord> records = memory.allRecords();
        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(record -> record.position().equals(
                new WorldPosition(5, 64, 3, "minecraft:overworld"))), "同一片留第一次记下的位置");
    }

    @Test
    void 早先存下的重复产地_读进来并成一条并写回(@TempDir Path temp) throws IOException {
        DocumentStore store = store(temp);
        String world = "a".repeat(64);
        // 按片认之前存下的册子：同一片树林记了 30 条，另有一只箱子。
        List<MemoryRecord> stored = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            stored.add(new MemoryRecord(MemoryKind.SITE, new WorldPosition(i, 64, i, "minecraft:overworld"), null,
                    List.of("木头"), MemoryOrigin.SEEN, NOW.plusSeconds(i)));
        }
        stored.add(new MemoryRecord(MemoryKind.CONTAINER, CHEST, "minecraft:chest", null, MemoryOrigin.SEEN, NOW));
        MemoryCodec codec = new MemoryCodec();
        store.write(WorldMemory.SCOPE, world, "memory", codec.encode(new MemoryBook(stored, Map.of(), Map.of())), false);

        var memory = new WorldMemory(store, world);
        assertEquals(2, memory.allRecords().size());
        // 并过的册子存盘写回，文档里也只剩两条。
        memory.flush();
        assertEquals(2, codec.decode(store.read(WorldMemory.SCOPE, world, "memory", 1 << 20)).records().size());
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
