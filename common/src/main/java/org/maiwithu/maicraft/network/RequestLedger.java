// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** No entry eviction within a session: losing a receipt must never enable a second mutation. */
final class RequestLedger {
    static final int MAX_REQUESTS = 512;
    static final int RECENT_READ_RESULTS = 8;
    private static final int MAX_MUTATION_CHARS = 524288;
    private record Entry(String fingerprint, JsonObject receipt, boolean readOnly) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Boolean> recentReads = new LinkedHashMap<>();
    private int mutationChars;

    JsonObject lookup(String requestId) {
        Entry entry = entries.get(requestId);
        return entry == null ? null : entry.receipt().deepCopy();
    }

    boolean matches(String requestId, String fingerprint) {
        Entry entry = entries.get(requestId);
        // Null fingerprints represent cancel-before-request tombstones.
        return entry != null && (entry.fingerprint() == null || entry.fingerprint().equals(fingerprint));
    }

    boolean full() {
        return full(false);
    }

    boolean full(boolean readOnly) {
        return entries.size() >= MAX_REQUESTS
                || !readOnly && mutationChars + ProtocolJson.MAX_ENVELOPE_CHARS > MAX_MUTATION_CHARS;
    }

    boolean readOnlyHistory() { return entries.values().stream().allMatch(Entry::readOnly); }
    int remainingRequests() { return Math.max(0, MAX_REQUESTS - entries.size()); }

    void store(String requestId, String fingerprint, JsonObject receipt) {
        store(requestId, fingerprint, receipt, false);
    }

    void store(String requestId, String fingerprint, JsonObject receipt, boolean readOnly) {
        JsonObject copy = receipt.deepCopy();
        int size = ProtocolJson.encode(copy).length();
        Entry previous = entries.put(requestId, new Entry(fingerprint, copy, readOnly));
        if (previous != null && !previous.readOnly()) mutationChars -= previous.receipt().toString().length();
        if (!readOnly) mutationChars += size;
        if (readOnly && copy.has("result")) recentReads.put(requestId, true);
        while (recentReads.size() > RECENT_READ_RESULTS) {
            String oldest = recentReads.keySet().iterator().next();
            recentReads.remove(oldest);
            Entry entry = entries.get(oldest);
            JsonObject expired = entry.receipt().deepCopy();
            expired.remove("result");
            expired.addProperty("status", "failed");
            expired.addProperty("effect", "not_applied");
            expired.addProperty("code", "receipt_unavailable");
            expired.addProperty("message", "The original read result expired; obtain a new independent observation");
            JsonObject result = new JsonObject();
            result.addProperty("complete", false);
            expired.add("result", result);
            entries.put(oldest, new Entry(entry.fingerprint(), expired, true));
        }
    }

    static String fingerprint(JsonObject request) {
        JsonObject effect = new JsonObject();
        for (String key : new String[]{"sessionId", "dimension", "operationId", "version", "controlGeneration", "body"})
            effect.add(key, request.get(key));
        try {
            byte[] bytes = ProtocolJson.canonical(effect).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
