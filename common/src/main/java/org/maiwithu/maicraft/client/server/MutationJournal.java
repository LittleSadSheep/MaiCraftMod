// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable unresolved request identities; it never stores executable requests or replays them. */
public final class MutationJournal implements MutationPersistence {
    private static final long MAX_BYTES = 1_048_576;
    private final Path path;
    private final Path temporary;
    private final Map<UUID, JsonObject> unresolved = new LinkedHashMap<>();
    private String failure = "";
    private String server = "unbound";
    private String player = "unbound";
    private String dimension = "unbound";

    public MutationJournal(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.temporary = this.path.resolveSibling(this.path.getFileName() + ".pending");
        try {
            load(this.path);
            // A crash before atomic replacement may leave additional pre-submission identities.
            load(temporary);
        } catch (IOException | RuntimeException corrupt) {
            failure = "mutation_journal_unreadable";
        }
    }

    public void bind(String server, String player, String dimension) {
        this.server = java.util.Objects.requireNonNull(server);
        this.player = java.util.Objects.requireNonNull(player);
        this.dimension = java.util.Objects.requireNonNull(dimension);
    }

    @Override public void beforeSubmission(ClientRequestReceipt receipt) {
        if (!receipt.operation.mutating()) return;
        if (!failure.isEmpty()) throw new IllegalStateException(failure);
        if (unresolved.size() >= 512) throw new IllegalStateException("mutation journal capacity reached");
        JsonObject entry = new JsonObject();
        entry.addProperty("request_id", receipt.id().toString());
        entry.addProperty("operation", receipt.operation.id());
        entry.addProperty("version", receipt.operation.version());
        entry.addProperty("server", server);
        entry.addProperty("player", player);
        entry.addProperty("dimension", dimension);
        entry.addProperty("backend", receipt.backend.name().toLowerCase(java.util.Locale.ROOT));
        entry.addProperty("body_digest", digest(receipt.arguments.toString()));
        entry.addProperty("recorded_at", java.time.Instant.now().toString());
        if (receipt.scope != null) {
            entry.addProperty("session_id", receipt.scope.sessionId());
            entry.addProperty("dimension", receipt.scope.dimension());
        }
        unresolved.put(receipt.id(), entry);
        persist();
    }

    @Override public void afterObservation(ClientRequestReceipt receipt) {
        if (!receipt.operation.mutating() || !receipt.authoritative() || !unresolved.containsKey(receipt.id())) return;
        JsonObject previous = unresolved.remove(receipt.id());
        try { persist(); }
        catch (RuntimeException failed) { unresolved.put(receipt.id(), previous); }
    }

    @Override public boolean unresolved() { return !failure.isEmpty() || !unresolved.isEmpty(); }

    @Override public JsonObject report() {
        JsonObject report = new JsonObject();
        report.addProperty("blocked", unresolved());
        report.addProperty("reason", failure.isEmpty() && !unresolved.isEmpty()
                ? "unresolved_mutation_requires_authoritative_reconciliation" : failure);
        JsonArray entries = new JsonArray();
        unresolved.values().forEach(entry -> entries.add(entry.deepCopy()));
        report.add("unresolved_requests", entries);
        return report;
    }

    private void load(Path source) throws IOException {
        if (!Files.exists(source)) return;
        if (Files.size(source) > MAX_BYTES) throw new IOException("mutation journal too large");
        JsonObject stored = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
        if (!stored.has("schema") || stored.get("schema").getAsInt() != 1
                || !stored.has("unresolved") || !stored.get("unresolved").isJsonArray())
            throw new IOException("invalid mutation journal");
        for (var raw : stored.getAsJsonArray("unresolved")) {
            JsonObject entry = raw.getAsJsonObject();
            UUID id = UUID.fromString(entry.get("request_id").getAsString());
            if (!entry.has("server") || !entry.has("operation")) throw new IOException("missing journal identity");
            unresolved.put(id, entry.deepCopy());
            if (unresolved.size() > 512) throw new IOException("mutation journal capacity exceeded");
        }
    }

    private void persist() {
        if (!failure.isEmpty()) throw new IllegalStateException(failure);
        JsonObject contents = new JsonObject();
        contents.addProperty("schema", 1);
        JsonArray entries = new JsonArray();
        unresolved.values().forEach(entry -> entries.add(entry.deepCopy()));
        contents.add("unresolved", entries);
        byte[] bytes = contents.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > MAX_BYTES) throw new IOException("mutation journal too large");
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Refuse mutation if this filesystem cannot durably replace the journal atomically.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            failure = "mutation_journal_write_failed";
            throw new IllegalStateException(failure, failed);
        }
    }

    private static String digest(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
