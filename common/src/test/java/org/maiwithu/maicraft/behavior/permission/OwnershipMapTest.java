// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ReceivedConfirmations;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按区块记的方块归属：区块问过才算知道，回答按放置人表与四个数一格读回。 */
class OwnershipMapTest {

    private static final String SELF = "self-uuid";
    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT), SELF);
        assertFalse(map.known("minecraft:overworld", 5, 64, 5));
        assertTrue(map.whoPlaced("minecraft:overworld", 5, 64, 5).isEmpty());
    }

    @Test
    void 没写维度的位置按角色此刻所在的维度查() {
        // 挖矿、收庄稼报来的格子只写坐标、不写维度：按角色所在维度查到的归属同样算数。
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT), SELF);
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
    void 自己刚放下的门_当刻认作自己放的_连上半() {
        // 区块三十秒才重问一次：服务端推来"你放了这扇门"就先记下，不等重问，也不靠"像玩家放的门"去猜。
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT), SELF);
        map.remember(new OwnershipMap.ChunkKey(DIM, 1, 0), Map.of(), 1_000);
        map.ownChange(new ReceivedConfirmations.ServerConfirmation("place", DIM, 17, 63, 15, "minecraft:spruce_door", 5), 2_000);

        assertEquals(SELF, map.whoPlaced(DIM, 17, 63, 15).orElseThrow().playerId());
        assertEquals(SELF, map.whoPlaced(DIM, 17, 64, 15).orElseThrow().playerId(), "门的上半一起算");
        assertTrue(map.whoPlaced(DIM, 17, 65, 15).isEmpty());
        map.ownChange(new ReceivedConfirmations.ServerConfirmation("place", DIM, 40, 63, 40, "minecraft:stone", 6), 2_000);
        assertTrue(map.known(DIM, 40, 63, 40), "还没问过的区块里，自己刚放下的那一格不算拿不准");
        assertFalse(map.known(DIM, 41, 63, 40));
    }

    @Test
    void 比自己放下更晚的询问有了回答_以回答为准() {
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT), SELF);
        OwnershipMap.ChunkKey chunk = new OwnershipMap.ChunkKey(DIM, 1, 0);
        map.ownChange(new ReceivedConfirmations.ServerConfirmation("place", DIM, 17, 63, 15, "minecraft:stone", 5), 2_000);
        map.remember(chunk, Map.of(), 1_500);
        assertEquals(SELF, map.whoPlaced(DIM, 17, 63, 15).orElseThrow().playerId(), "回答是放下之前问的，里面还没有这一格");
        map.remember(chunk, Map.of(BlockPos.asLong(17, 63, 15), "bob"), 2_500);
        assertEquals("bob", map.whoPlaced(DIM, 17, 63, 15).orElseThrow().playerId(), "之后问到的回答说了算");
    }

    @Test
    void 自己拆掉的格不再记成自己放的() {
        OwnershipMap map = new OwnershipMap(new ServerLinkSession(NO_TRANSPORT), SELF);
        map.remember(new OwnershipMap.ChunkKey(DIM, 1, 0), Map.of(), 1_000);
        map.ownChange(new ReceivedConfirmations.ServerConfirmation("place", DIM, 17, 63, 15, "minecraft:stone", 5), 2_000);
        map.ownChange(new ReceivedConfirmations.ServerConfirmation("break", DIM, 17, 63, 15, "minecraft:stone", 6), 2_100);
        assertTrue(map.whoPlaced(DIM, 17, 63, 15).isEmpty());
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
