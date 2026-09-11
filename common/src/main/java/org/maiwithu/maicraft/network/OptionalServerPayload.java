// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Stable optional bootstrap. Feature compatibility is negotiated inside the envelope. */
public record OptionalServerPayload(String json) implements CustomPacketPayload {
    public static final Type<OptionalServerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("maicraft", "optional_server"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OptionalServerPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, ProtocolJson.MAX_ENVELOPE_CHARS),
            buffer -> new OptionalServerPayload(buffer.readUtf(ProtocolJson.MAX_ENVELOPE_CHARS)));

    public OptionalServerPayload {
        if (json == null || json.length() > ProtocolJson.MAX_ENVELOPE_CHARS)
            throw new IllegalArgumentException("Envelope too large");
    }

    public static OptionalServerPayload of(JsonObject envelope) {
        return new OptionalServerPayload(ProtocolJson.encode(envelope));
    }

    public JsonObject envelope() { return ProtocolJson.decode(json); }
    @Override public Type<OptionalServerPayload> type() { return TYPE; }
}
