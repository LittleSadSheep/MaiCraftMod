// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;

/**
 * 方块归属的读端：客户端向服务端 MaiCraft 发只读查询"这格是谁放的"，按归属记录作答。
 *
 * <p>查询是异步的：第一次问只是把请求发出去，返回空（拿不准按受保护处理）；之后每次问
 * 都会核对在途请求有没有结算，结算了才给出放它的人。查不到归属、请求失败或服务端没记到，
 * 都返回空——调用方再靠区域与玩家放置推断兜底。给过的答案按位置短记一会儿，同一格
 * 在冷却时间内不重复发请求。只读请求不改世界，但发起与读取都必须在控制循环的刻内进行
 * （会话按客户端刻推进）。
 */
public final class OwnershipQueries implements ReadsBlockOwnership {

    // 服务端没记到（模组装上之前放的、记录被淘汰）时，短时间内不再为同一格重发请求。
    private static final long RETRY_COOLDOWN_MILLIS = 5_000L;

    private final ServerLinkSession session;
    /** 在途与已冷却的查询：位置 → 请求编号或最近一次失败时刻。 */
    private final Map<Key, UUID> pending = new HashMap<>();
    private final Map<Key, Long> failedAtMillis = new HashMap<>();

    /** 一格的位置键：归属按维度分开记。 */
    private record Key(String dimension, int x, int y, int z) {}

    public OwnershipQueries(ServerLinkSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
        Key key = new Key(dimension, x, y, z);
        UUID requestId = pending.get(key);
        if (requestId != null) {
            Optional<ClientRequest.Snapshot> snapshot = session.router().poll(requestId);
            if (snapshot.isEmpty()) {
                // 请求已从记录里退场（世界换了、会话关了）：拿不准，按受保护处理。
                pending.remove(key);
                return Optional.empty();
            }
            if (!snapshot.get().settled()) {
                return Optional.empty();
            }
            pending.remove(key);
            Optional<PlacedBy> answer = parse(snapshot.get());
            if (answer.isEmpty()) {
                failedAtMillis.put(key, System.currentTimeMillis());
            }
            return answer;
        }
        Long failed = failedAtMillis.get(key);
        if (failed != null) {
            if (System.currentTimeMillis() - failed < RETRY_COOLDOWN_MILLIS) {
                return Optional.empty();
            }
            failedAtMillis.remove(key);
        }
        submit(dimension, x, y, z, key);
        return Optional.empty();
    }

    private void submit(String dimension, int x, int y, int z, Key key) {
        JsonObject position = new JsonObject();
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", z);
        JsonObject body = new JsonObject();
        body.add("position", position);
        // 只读请求：不改世界，返回空只表示"这一刻还拿不准"。
        ClientRequest request = session.router().submit("ownership.query", body, false);
        pending.put(key, request.id());
    }

    // 结算快照换成归属：服务端记到了给放置者编号，没记到（owned=false）或请求失败给空。
    static Optional<PlacedBy> parse(ClientRequest.Snapshot snapshot) {
        if (snapshot.status() != ClientRequest.Status.SUCCEEDED) {
            return Optional.empty();
        }
        JsonObject result = snapshot.result();
        if (!result.has("owned") || !result.get("owned").getAsBoolean() || !result.has("owner")) {
            return Optional.empty();
        }
        return Optional.of(new PlacedBy(result.get("owner").getAsString()));
    }
}
