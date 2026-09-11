// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** Counts are excluded; all persisted data components participate in identity. */
public final class ResourceIdentity {
    private ResourceIdentity() {}

    public static JsonObject item(ItemStack stack, HolderLookup.Provider registries) {
        JsonObject result = base("items", stack.isEmpty() ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (!stack.isEmpty()) {
            if (stack.getComponentsPatch().entrySet().stream().anyMatch(entry -> entry.getKey().isTransient())) {
                throw new IllegalArgumentException("Transient item components cannot be represented as a complete persisted resource identity");
            }
            JsonObject encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries),
                    stack.copyWithCount(1)).getOrThrow().getAsJsonObject();
            result.add("components", encoded.has("components") ? encoded.get("components") : new JsonObject());
        }
        return result;
    }

    public static JsonObject base(String kind, String id) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind);
        result.addProperty("id", id);
        result.add("components", new JsonObject());
        return result;
    }

    public static String key(JsonObject identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(identity).getBytes(StandardCharsets.UTF_8));
            return identity.get("kind").getAsString() + ":" + identity.get("id").getAsString()
                    + "#" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonArray()) {
            java.util.StringJoiner result = new java.util.StringJoiner(",", "[", "]");
            value.getAsJsonArray().forEach(entry -> result.add(canonical(entry)));
            return result.toString();
        }
        if (!value.isJsonObject()) return value.toString();
        java.util.StringJoiner result = new java.util.StringJoiner(",", "{", "}");
        new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, entry) ->
                result.add(new com.google.gson.JsonPrimitive(key) + ":" + canonical(entry)));
        return result.toString();
    }

    public static JsonObject resource(JsonObject identity, long amount, Long capacity, String unit,
                                      String storage, String side, String membership) {
        JsonObject result = new JsonObject();
        result.add("identity", identity);
        result.addProperty("resource_id", key(identity));
        result.addProperty("amount", amount);
        result.add("capacity", capacity == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(capacity));
        result.addProperty("unit", unit);
        result.addProperty("storage_id", storage);
        result.addProperty("side", side);
        result.addProperty("membership", membership);
        result.addProperty("provenance", "server_native");
        result.addProperty("external_change_attribution", "unknown");
        return result;
    }
}
