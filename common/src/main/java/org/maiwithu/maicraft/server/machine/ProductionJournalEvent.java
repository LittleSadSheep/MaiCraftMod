// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable encoded payload shared by all participating endpoint histories. */
record ProductionJournalEvent(long sequence, String encoded, int nodes) {
    static final int MAX_CHARS = 24_000;
    static final int MAX_NODES = 4096;
    private record Node(JsonElement value, int depth) {}

    static Set<String> subjects(JsonObject value) {
        Set<String> result = new LinkedHashSet<>();
        for (String field : new String[]{"producer", "source", "destination"}) {
            if (!value.has(field)) continue;
            JsonElement raw = value.get(field);
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()
                    || raw.getAsString().isBlank() || raw.getAsString().length() > 128) {
                throw new IllegalArgumentException("Invalid native production endpoint");
            }
            result.add(raw.getAsString());
        }
        if (result.isEmpty()) throw new IllegalArgumentException("A native event needs an endpoint");
        return result;
    }

    static ProductionJournalEvent encode(JsonObject source, String scope, long sequence) {
        JsonObject value = new JsonObject();
        source.entrySet().forEach(entry -> value.add(entry.getKey(), entry.getValue()));
        value.addProperty("scope", scope); value.addProperty("sequence", sequence);
        int nodes = complexity(value);
        if (nodes < 0) return null;
        String encoded = value.toString();
        return encoded.length() > MAX_CHARS ? null : new ProductionJournalEvent(sequence, encoded, nodes);
    }

    private static int complexity(JsonElement value) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(value, 0));
        int nodes = 0, chars = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (++nodes > MAX_NODES || node.depth() > 18) return -1;
            JsonElement entry = node.value();
            if (entry.isJsonObject()) {
                if (entry.getAsJsonObject().size() + pending.size() + nodes > MAX_NODES) return -1;
                for (var field : entry.getAsJsonObject().entrySet()) {
                    if (field.getKey().length() > 128) return -1;
                    chars += field.getKey().length();
                    pending.add(new Node(field.getValue(), node.depth() + 1));
                }
            } else if (entry.isJsonArray()) {
                if (entry.getAsJsonArray().size() + pending.size() + nodes > MAX_NODES) return -1;
                for (JsonElement item : entry.getAsJsonArray()) pending.add(new Node(item, node.depth() + 1));
            } else chars += entry.isJsonNull() ? 4 : entry.getAsString().length();
            if (chars > MAX_CHARS) return -1;
        }
        return nodes;
    }

    JsonObject json() { return JsonParser.parseString(encoded).getAsJsonObject(); }
}
