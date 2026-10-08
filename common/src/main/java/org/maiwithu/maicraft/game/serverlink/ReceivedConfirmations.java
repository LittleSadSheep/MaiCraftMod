// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.List;

/**
 * 收到的服务端确认：服务端推送的"这个玩家刚才破坏 / 放置 / 交互了哪一格"在这里排队，
 * 供许可与保护、交互确认的判断读取。容量有界，只保留最近的确认。
 */
public final class ReceivedConfirmations {
    /** 最多保留的确认条数；旧的让位给新的。 */
    public static final int MAX_CONFIRMATIONS = 64;

    /** 一条服务端确认：玩家对哪个维度的哪一格做了什么，服务端刻号给出先后。 */
    public record ServerConfirmation(String action, String dimension, int x, int y, int z,
                                     String blockId, long serverTick) {}

    private final ArrayDeque<ServerConfirmation> confirmations = new ArrayDeque<>();

    public void receive(JsonObject envelope) {
        try {
            var position = envelope.getAsJsonObject("position");
            confirmations.addLast(new ServerConfirmation(
                    ServerCapabilityState.text(envelope, "action"),
                    ServerCapabilityState.text(envelope, "dimension"),
                    position.get("x").getAsInt(), position.get("y").getAsInt(), position.get("z").getAsInt(),
                    ServerCapabilityState.text(envelope, "block"),
                    envelope.has("serverTick") ? envelope.get("serverTick").getAsLong() : -1));
            while (confirmations.size() > MAX_CONFIRMATIONS) confirmations.removeFirst();
        } catch (RuntimeException malformed) {
            // 缺字段的确认不可信，直接丢弃；不影响其余确认。
        }
    }

    public List<ServerConfirmation> recent() {
        return List.copyOf(confirmations);
    }
}
