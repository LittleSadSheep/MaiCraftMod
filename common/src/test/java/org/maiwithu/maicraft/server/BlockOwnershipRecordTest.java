// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 方块归属记录的离线场景：写入、按维度查询、覆盖与容量淘汰。 */
class BlockOwnershipRecordTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void 记录之后能查到是谁放的() {
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        var position = new BlockPos(10, 64, -3);
        record.recordPlacement(OVERWORLD, position, alice, 500);
        Optional<BlockOwnershipRecord.Owner> owner = record.ownerOf(OVERWORLD, position);
        assertTrue(owner.isPresent());
        assertEquals(alice, owner.get().playerId());
        assertEquals(500, owner.get().tick());
    }

    @Test
    void 不同维度互不干扰() {
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        var position = new BlockPos(0, 64, 0);
        record.recordPlacement(OVERWORLD, position, alice, 1);
        assertTrue(record.ownerOf(NETHER, position).isEmpty());
    }

    @Test
    void 同一格被替换时以后来的为准() {
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var position = new BlockPos(1, 2, 3);
        record.recordPlacement(OVERWORLD, position, alice, 10);
        record.recordPlacement(OVERWORLD, position, bob, 20);
        var owner = record.ownerOf(OVERWORLD, position).orElseThrow();
        assertEquals(bob, owner.playerId());
        assertEquals(20, owner.tick());
    }

    @Test
    void 没记到的格子返回空() {
        var record = new BlockOwnershipRecord();
        assertTrue(record.ownerOf(OVERWORLD, new BlockPos(7, 7, 7)).isEmpty());
    }

    @Test
    void 超出容量后淘汰最早的记录() {
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        var oldest = new BlockPos(0, 0, 0);
        var kept = new BlockPos(BlockOwnershipRecord.MAX_ENTRIES_PER_DIMENSION, 0, 0);
        record.recordPlacement(OVERWORLD, oldest, alice, 1);
        // 再放满一整个容量的方块，最早的一条被淘汰，最后一条保留。
        for (int i = 1; i <= BlockOwnershipRecord.MAX_ENTRIES_PER_DIMENSION; i++) {
            record.recordPlacement(OVERWORLD, new BlockPos(i, 0, 0), alice, i + 1);
        }
        assertTrue(record.ownerOf(OVERWORLD, oldest).isEmpty());
        assertTrue(record.ownerOf(OVERWORLD, kept).isPresent());
    }

    @Test
    void 存盘后重启还能读回记录(@TempDir Path temp) {
        // 存进存档、重启后读回：玩家盖的房子不会因为服务器重启就变成"不知道是谁的"。
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        record.recordPlacement(OVERWORLD, new BlockPos(1, 64, 1), alice, 10);
        record.recordPlacement(NETHER, new BlockPos(-5, 70, 3), bob, 20);
        var file = new OwnershipFile(temp.resolve("data").resolve("owners.dat"));

        file.saveIfDirty(record);
        var restored = file.load();

        assertEquals(alice, restored.ownerOf(OVERWORLD, new BlockPos(1, 64, 1)).orElseThrow().playerId());
        assertEquals(20, restored.ownerOf(NETHER, new BlockPos(-5, 70, 3)).orElseThrow().tick());
        assertFalse(record.dirty(), "存过盘就没有待存的改动");
    }

    @Test
    void 方块被拆掉就忘掉它的归属() {
        var record = new BlockOwnershipRecord();
        var position = new BlockPos(2, 64, 2);
        record.recordPlacement(OVERWORLD, position, UUID.randomUUID(), 1);
        record.forget(OVERWORLD, position);
        assertTrue(record.ownerOf(OVERWORLD, position).isEmpty(), "方块拆了，这一格不再算谁的");
    }

    @Test
    void 按区块问_只给这个区块里有主的格子() {
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        record.recordPlacement(OVERWORLD, new BlockPos(1, 64, 1), alice, 1);
        record.recordPlacement(OVERWORLD, new BlockPos(15, 70, 15), alice, 2);
        record.recordPlacement(OVERWORLD, new BlockPos(16, 64, 0), alice, 3);
        record.recordPlacement(NETHER, new BlockPos(2, 64, 2), alice, 4);
        assertEquals(Set.of(new BlockPos(1, 64, 1), new BlockPos(15, 70, 15)),
                record.ownedInChunk(OVERWORLD, 0, 0).keySet());
        // 拆掉的格子不再算有主。
        record.forget(OVERWORLD, new BlockPos(1, 64, 1));
        assertEquals(Set.of(new BlockPos(15, 70, 15)), record.ownedInChunk(OVERWORLD, 0, 0).keySet());
    }

    @Test
    void 存档读回后按区块也问得到() {
        var record = new BlockOwnershipRecord();
        var alice = UUID.randomUUID();
        record.recordPlacement(OVERWORLD, new BlockPos(-3, 64, -3), alice, 7);
        var restored = BlockOwnershipRecord.fromTag(record.toTag());
        assertEquals(alice, restored.ownedInChunk(OVERWORLD, -1, -1).get(new BlockPos(-3, 64, -3)).playerId());
    }
}
