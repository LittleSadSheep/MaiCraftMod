// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import org.maiwithu.maicraft.intent.persistence.LegacyMemoryFiles;

/** 持久化结果未决的请求身份，用于后续核对；不保存或重放可执行请求。 */
public final class MutationJournal implements MutationPersistence {
    private static final int MAX_BYTES = 1_048_576;
    private final Path path;
    private final Path temporary;
    private final MemoryDatabase database;
    private final Map<UUID, JsonObject> unresolved = new LinkedHashMap<>();
    private String failure = "";
    private String server = "unbound";
    private String player = "unbound";
    private String dimension = "unbound";

    public MutationJournal(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.temporary = this.path.resolveSibling(this.path.getFileName() + ".pending");
        this.database = new MemoryDatabase(this.path.getParent().resolve(MemoryDatabase.FILE_NAME));
        try {
            String stored = readDatabase();
            if (stored != null) load(stored);
            else {
                String original = LegacyMemoryFiles.read(this.path, MAX_BYTES);
                String pending = LegacyMemoryFiles.read(temporary, MAX_BYTES);
                // 旧替换流程崩溃后可能留下两份未决身份；先全部合并校验，再一次入库，保留两份源文件。
                if (original != null) load(original);
                if (pending != null) load(pending);
                if (original != null || pending != null) {
                    database.writeRecord("server-mutations", "client", key(), encode().toString(), true);
                    unresolved.clear(); load(readDatabase());
                }
            }
        } catch (IOException | RuntimeException corrupt) {
            failure = "mutation_journal_unreadable";
        }
    }

    public void bind(String server, String player, String dimension) {
        this.server = Objects.requireNonNull(server);
        this.player = Objects.requireNonNull(player);
        this.dimension = Objects.requireNonNull(dimension);
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
        entry.addProperty("backend", receipt.backend.name().toLowerCase(Locale.ROOT));
        entry.addProperty("body_digest", digest(receipt.arguments.toString()));
        entry.addProperty("recorded_at", Instant.now().toString());
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

    private String key() { return path.getFileName().toString(); }
    private String readDatabase() throws IOException { return database.readRecord("server-mutations", "client", key(), MAX_BYTES); }

    private void load(String json) throws IOException {
        JsonObject stored = JsonParser.parseString(json).getAsJsonObject();
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

    private JsonObject encode() throws IOException {
        JsonObject contents = new JsonObject();
        contents.addProperty("schema", 1);
        JsonArray entries = new JsonArray();
        unresolved.values().forEach(entry -> entries.add(entry.deepCopy()));
        contents.add("unresolved", entries);
        if (contents.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("mutation journal too large");
        return contents;
    }

    private void persist() {
        if (!failure.isEmpty()) throw new IllegalStateException(failure);
        try {
            // 发送请求前等待真实 SQLite 提交；已核账的空集合也保存，避免重启时再次导入过时未决记录。
            database.writeRecord("server-mutations", "client", key(), encode().toString(), false);
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
