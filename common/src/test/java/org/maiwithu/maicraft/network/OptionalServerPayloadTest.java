// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Exercises the actual Minecraft codec, including UTF-8 wire size and response/request asymmetry. */
public final class OptionalServerPayloadTest {
    public static void main(String[] args) {
        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("body", "汉".repeat(8000));
        ProtocolJson.encodeRequest(request);
        int requestBytes = roundTrip(request);
        check(requestBytes < 32767, "Serverbound messages must fit Minecraft's smaller wire budget");

        JsonObject response = new JsonObject();
        response.addProperty("kind", "receipt");
        response.addProperty("snapshot", "汉".repeat(55000));
        int responseBytes = roundTrip(response);
        check(responseBytes > 32767 && responseBytes < 1048576, "Large snapshots must use the clientbound budget");
        try { ClientProtocolBridge.send(response); throw new AssertionError("Large serverbound envelope was accepted"); }
        catch (IllegalArgumentException expected) { /* rejected before looking up a client transport */ }

        RegistryFriendlyByteBuf invalid = buffer();
        try {
            invalid.writeUtf("x".repeat(ProtocolJson.MAX_ENVELOPE_CHARS + 1), ProtocolJson.MAX_ENVELOPE_CHARS + 1);
            try { OptionalServerPayload.CODEC.decode(invalid); throw new AssertionError("Oversized payload decoded"); }
            catch (RuntimeException expected) { /* bounded before JSON allocation */ }
        } finally { invalid.release(); }
        System.out.println("OptionalServerPayloadTest passed (request " + requestBytes + " bytes, response " + responseBytes + " bytes)");
    }

    private static int roundTrip(JsonObject envelope) {
        RegistryFriendlyByteBuf wire = buffer();
        try {
            OptionalServerPayload.CODEC.encode(wire, OptionalServerPayload.of(envelope));
            int bytes = wire.readableBytes();
            check(OptionalServerPayload.CODEC.decode(wire).envelope().equals(envelope), "Codec changed the envelope");
            check(wire.readableBytes() == 0, "Codec left unread bytes");
            return bytes;
        } finally { wire.release(); }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
