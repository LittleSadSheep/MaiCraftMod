// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;

/**
 * 方块归属读端的结果解析：服务端结算快照换成"谁放的"，没记到与失败都返回空。
 */
class OwnershipQueriesTest {

    private static ClientRequest.Snapshot snapshot(ClientRequest.Status status, JsonObject result) {
        return new ClientRequest.Snapshot(UUID.randomUUID(), "ownership.query", 1, false,
                status, ClientRequest.Effect.NOT_APPLIED, false, "", "", 0L, result);
    }

    private static JsonObject ownedResult(boolean owned, String owner) {
        JsonObject result = new JsonObject();
        result.addProperty("owned", owned);
        if (owner != null) {
            result.addProperty("owner", owner);
            result.addProperty("placedTick", 123L);
        }
        return result;
    }

    @Test
    void 服务端记到了给放置者() {
        var answer = OwnershipQueries.parse(snapshot(ClientRequest.Status.SUCCEEDED,
                ownedResult(true, "069a79f4-44e9-4726-a5be-fca90e38aaf5")));
        assertTrue(answer.isPresent());
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", answer.get().playerId());
    }

    @Test
    void 服务端没记到返回空() {
        assertTrue(OwnershipQueries.parse(snapshot(ClientRequest.Status.SUCCEEDED,
                ownedResult(false, null))).isEmpty());
        // 有 owned 但没带 owner 字段的残缺结果同样不给答案。
        assertTrue(OwnershipQueries.parse(snapshot(ClientRequest.Status.SUCCEEDED,
                ownedResult(true, null))).isEmpty());
    }

    @Test
    void 请求失败返回空() {
        assertTrue(OwnershipQueries.parse(snapshot(ClientRequest.Status.FAILED,
                ownedResult(true, "069a79f4-44e9-4726-a5be-fca90e38aaf5"))).isEmpty());
    }
}
