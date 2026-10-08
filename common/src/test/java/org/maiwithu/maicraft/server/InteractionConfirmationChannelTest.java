// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.serverlink.ReceivedConfirmations;

/** 交互确认通道的离线场景：服务端信封的形状，与客户端解码后的读数一致。 */
class InteractionConfirmationChannelTest {

    @Test
    void 确认信封携带动作维度坐标方块与刻号() {
        JsonObject envelope = ServerConfirmations.confirmationEnvelope(
                "place", "minecraft:overworld", new BlockPos(12, 64, -8), "minecraft:stone", 12345);
        assertEquals("confirmation", envelope.get("kind").getAsString());
        assertEquals("place", envelope.get("action").getAsString());
        assertEquals("minecraft:overworld", envelope.get("dimension").getAsString());
        assertEquals(12, envelope.getAsJsonObject("position").get("x").getAsInt());
        assertEquals(64, envelope.getAsJsonObject("position").get("y").getAsInt());
        assertEquals(-8, envelope.getAsJsonObject("position").get("z").getAsInt());
        assertEquals("minecraft:stone", envelope.get("block").getAsString());
        assertEquals(12345, envelope.get("serverTick").getAsLong());
    }

    @Test
    void 客户端解码后能读到同样的确认() {
        JsonObject envelope = ServerConfirmations.confirmationEnvelope(
                "break", "minecraft:the_nether", new BlockPos(1, 2, 3), "minecraft:netherrack", 9);
        var received = new ReceivedConfirmations();
        received.receive(envelope);
        var confirmation = received.recent().getFirst();
        assertEquals("break", confirmation.action());
        assertEquals("minecraft:the_nether", confirmation.dimension());
        assertEquals(1, confirmation.x());
        assertEquals(2, confirmation.y());
        assertEquals(3, confirmation.z());
        assertEquals("minecraft:netherrack", confirmation.blockId());
        assertEquals(9, confirmation.serverTick());
    }

    @Test
    void 缺字段的确认被丢弃不计数() {
        var received = new ReceivedConfirmations();
        received.receive(new JsonObject());
        assertEquals(0, received.recent().size());
    }

    @Test
    void 只保留最近的确认() {
        var received = new ReceivedConfirmations();
        for (int i = 0; i < ReceivedConfirmations.MAX_CONFIRMATIONS + 10; i++) {
            JsonObject envelope = ServerConfirmations.confirmationEnvelope(
                    "interact", "minecraft:overworld", new BlockPos(i, 0, 0), "minecraft:stone", i);
            received.receive(envelope);
        }
        assertEquals(ReceivedConfirmations.MAX_CONFIRMATIONS, received.recent().size());
        assertEquals(10, received.recent().getFirst().x());
        assertEquals(ReceivedConfirmations.MAX_CONFIRMATIONS + 9,
                received.recent().getLast().x());
    }
}
