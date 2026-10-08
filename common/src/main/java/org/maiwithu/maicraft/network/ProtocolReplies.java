// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;

final class ProtocolReplies {
    private ProtocolReplies() {}

    static JsonObject response(String kind, JsonObject request, String status, String effect, long tick) {
        JsonObject reply = new JsonObject();
        reply.addProperty("bootstrap", ProtocolJson.BOOTSTRAP);
        reply.addProperty("kind", kind);
        for (String key : new String[]{"sessionId", "requestId", "operationId", "version", "dimension", "controlGeneration", "clientNonce"}) {
            if (request.has(key) && request.get(key).isJsonPrimitive()
                    && request.get(key).getAsString().length() <= 128) reply.add(key, request.get(key).deepCopy());
        }
        reply.addProperty("status", status);
        reply.addProperty("effect", effect);
        reply.addProperty("serverTick", tick);
        return reply;
    }

    static JsonObject error(JsonObject request, String status, String effect, String code, String message, long tick) {
        JsonObject reply = response("receipt", request, status, effect, tick);
        reply.addProperty("code", code);
        reply.addProperty("message", message == null ? code : message.substring(0, Math.min(message.length(), 256)));
        return reply;
    }

    static JsonObject reject(JsonObject request, String code, String message, long tick) {
        return error(request, "rejected", "not_applied", code, message, tick);
    }
}
