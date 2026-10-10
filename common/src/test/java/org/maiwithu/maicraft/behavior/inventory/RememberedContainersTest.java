// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记得的容器读端：世界记忆里的容器按距离给候选，别人的箱子不进候选。 */
class RememberedContainersTest {

    private static final String SELF = "00000000-0000-0000-0000-000000000001";
    private static final String STRANGER = "00000000-0000-0000-0000-000000000002";

    @TempDir
    Path temp;

    // 归属替身：只有"别人的箱子"那一格记着是别人放的，其余都算没记录的自家东西。
    private static ReadsBlockOwnership ownershipWithOtherAt(int x, int y, int z) {
        return new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int px, int py, int pz) {
                boolean other = px == x && py == y && pz == z;
                return Optional.of(new PlacedBy(other ? STRANGER : SELF));
            }
        };
    }

    private RememberedContainers containers(WorldMemory memory, WorldPosition standing, Protection protection) {
        return new RememberedContainers(memory, () -> standing, protection);
    }

    private WorldMemory memoryWithContainers() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        memory.rememberContainerSeen(new WorldPosition(10, -60, 12, "minecraft:overworld"),
                "minecraft:chest", Instant.now());
        memory.rememberContainerSeen(new WorldPosition(30, -60, 30, "minecraft:overworld"),
                "minecraft:barrel", Instant.now());
        return memory;
    }

    private Protection protectionOtherAt(int x, int y, int z) {
        return new Protection(ownershipWithOtherAt(x, y, z), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, SELF);
    }

    @Test
    void 距离以内的记得容器按名字与位置给候选() {
        WorldMemory memory = memoryWithContainers();
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(-1, -1, -1));

        List<KnownContainer> nearby = containers.within(16);
        assertEquals(1, nearby.size());
        assertEquals(10, nearby.getFirst().x());
        assertEquals(-60, nearby.getFirst().y());
        assertTrue(nearby.getFirst().name().contains("chest"));
    }

    @Test
    void 距离之外的不给() {
        WorldMemory memory = memoryWithContainers();
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(-1, -1, -1));
        // 最近的记忆箱隔着不到三格，两格之内没有；更远的木桶在 27 格外。
        assertTrue(containers.within(2).isEmpty());
    }

    @Test
    void 别人的箱子不进候选() {
        WorldMemory memory = memoryWithContainers();
        // (10, -60, 12) 是别人放的：虽然更近，也不往里塞东西；30 格外那只无主的照给。
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(10, -60, 12));
        List<KnownContainer> nearby = containers.within(64);
        assertEquals(1, nearby.size());
        assertEquals(30, nearby.getFirst().x());
    }
}
