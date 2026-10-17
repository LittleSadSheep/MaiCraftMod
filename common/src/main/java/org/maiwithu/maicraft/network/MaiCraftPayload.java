// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端与服务端 MaiCraft 之间的双向信封载荷；协商都在信封内的 JSON 里做。 */
public record MaiCraftPayload(String json) implements CustomPacketPayload {
    public static final Type<MaiCraftPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("maicraft", "link"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaiCraftPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, ProtocolJson.MAX_ENVELOPE_CHARS),
            buffer -> new MaiCraftPayload(buffer.readUtf(ProtocolJson.MAX_ENVELOPE_CHARS)));

    public MaiCraftPayload {
        if (json == null || json.length() > ProtocolJson.MAX_ENVELOPE_CHARS)
            throw new IllegalArgumentException("Envelope too large");
    }

    public static MaiCraftPayload of(JsonObject envelope) {
        return new MaiCraftPayload(ProtocolJson.encode(envelope));
    }

    public JsonObject envelope() { return ProtocolJson.decode(json); }
    @Override public Type<MaiCraftPayload> type() { return TYPE; }
}
