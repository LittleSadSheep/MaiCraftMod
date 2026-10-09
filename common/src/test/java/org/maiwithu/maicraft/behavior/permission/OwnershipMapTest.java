// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按区块记的方块归属：区块问过才算知道，回答按放置人表与四个数一格读回。 */
class OwnershipMapTest {

    private static final LinkTransport NO_TRANSPORT = new LinkTransport() {
        @Override public boolean available() {
            return false;
        }

        @Override public void send(JsonObject envelope) {
            throw new IllegalStateException("离线测试不发信封");
        }
    };

    @Test
    void 没问过的区块_拿不准() {
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT));
        assertFalse(map.known("minecraft:overworld", 5, 64, 5));
        assertTrue(map.whoPlaced("minecraft:overworld", 5, 64, 5).isEmpty());
    }

    @Test
    void 没写维度的位置按角色此刻所在的维度查() {
        // 挖矿、收庄稼报来的格子只写坐标、不写维度：按角色所在维度查到的归属同样算数。
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT));
        assertFalse(map.known(null, 5, 64, 5), "连维度都还不知道时说不知道，也不出错");
        map.standingIn("minecraft:overworld");
        map.remember(new OwnershipMap.ChunkKey("minecraft:overworld", 0, 0),
                Map.of(BlockPos.asLong(5, 64, 5), "alice"), System.currentTimeMillis());

        assertTrue(map.known(null, 5, 64, 5));
        assertEquals("alice", map.whoPlaced(null, 5, 64, 5).orElseThrow().playerId());
        assertTrue(map.whoPlaced(null, 6, 64, 5).isEmpty(), "问过的区块里没记录的格子就是没人放的");
        assertFalse(map.known("minecraft:the_nether", 5, 64, 5), "别的维度的同一坐标是另一块");
    }

    @Test
    void 回答按放置人表读回每一格() {
        JsonObject result = new JsonObject();
        JsonArray owners = new JsonArray();
        owners.add("alice");
        owners.add("bob");
        JsonArray cells = new JsonArray();
        for (int value : new int[] {1, 64, 2, 1, -3, 70, 4, 0}) cells.add(value);
        result.add("owners", owners);
        result.add("cells", cells);
        ClientRequest.Snapshot snapshot = new ClientRequest.Snapshot(UUID.randomUUID(), "ownership.chunk", 1, false,
                ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.NOT_APPLIED, false, null, null, 0, result);
        Optional<Map<Long, String>> parsed = OwnershipMap.parse(snapshot);
        assertEquals(Map.of(BlockPos.asLong(1, 64, 2), "bob", BlockPos.asLong(-3, 70, 4), "alice"), parsed.orElseThrow());
    }
}
