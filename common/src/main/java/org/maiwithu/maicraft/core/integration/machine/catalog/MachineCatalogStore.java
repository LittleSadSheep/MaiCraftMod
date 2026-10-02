// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletionException;
import org.maiwithu.maicraft.intent.persistence.LegacyMemoryFiles;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;

/** 后台恢复机器记忆；目录与蓝图共用 SQLite 事务，失败时保留整台机器的上一份档案。 */
final class MachineCatalogStore {
    record Loaded(CatalogCodec.Snapshot snapshot, boolean needsSave) {}
    private record Pending(CatalogCodec.Snapshot snapshot, CompletableFuture<Void> completion) {}
    private final Path directory;
    private final MemoryDatabase database;
    private final Executor executor;
    private final Map<String, Pending> latest = new LinkedHashMap<>();
    private boolean writing;

    MachineCatalogStore(Path directory, Path databaseFile, Executor executor) {
        this.directory = directory.toAbsolutePath().normalize(); this.database = new MemoryDatabase(databaseFile); this.executor = executor;
    }
    CompletableFuture<Loaded> load(String key) {
        synchronized (this) {
            Pending captured = latest.get(key);
            if (captured != null) return CompletableFuture.completedFuture(new Loaded(captured.snapshot(),
                    !captured.completion().isDone() || captured.completion().isCompletedExceptionally()));
        }
        return CompletableFuture.supplyAsync(() -> {
            Path path = path(key);
            try {
                var stored = readDatabase(key);
                if (stored != null) return new Loaded(stored, false);
                String legacy = LegacyMemoryFiles.read(path, CatalogLimits.FILE_BYTES);
                if (legacy == null) return new Loaded(new CatalogCodec.Snapshot(key, List.of(), List.of()), false);
                // 完整读出并校验所有引用图纸后才迁移；缺图纸时不发布一份无法恢复的数据库目录。
                write(CatalogCodec.decode(legacy, key, fingerprint -> readBlueprint(key, fingerprint)), true);
                return new Loaded(readDatabase(key), false);
            } catch (IOException | RuntimeException failure) { throw new CompletionException("catalog_load_failed", failure); }
        }, executor);
    }
    private CatalogCodec.Snapshot readDatabase(String key) throws IOException {
        return database.read("machines", key, CatalogLimits.FILE_BYTES, MachineBlueprint.MAX_BYTES,
                (json, blueprints) -> CatalogCodec.decode(json, key, blueprints));
    }
    synchronized CompletableFuture<Void> save(CatalogCodec.Snapshot snapshot) {
        if (!latest.containsKey(snapshot.identityKey()) && latest.size() >= 8) {
            var old = latest.entrySet().stream().filter(entry -> entry.getValue().completion().isDone()
                    && !entry.getValue().completion().isCompletedExceptionally()).map(Map.Entry::getKey).findFirst();
            old.ifPresent(latest::remove);
            if (latest.size() >= 8) return CompletableFuture.failedFuture(new IOException("catalog_pending_identity_limit"));
        }
        var completion = new CompletableFuture<Void>(); var pending = new Pending(snapshot, completion);
        Pending replaced = latest.put(snapshot.identityKey(), pending);
        if (replaced != null) replaced.completion().cancel(false);
        if (!writing) {
            writing = true;
            try { executor.execute(this::writePending); }
            catch (RuntimeException rejected) { writing = false; completion.completeExceptionally(rejected); }
        }
        return completion;
    }
    private void writePending() {
        while (true) {
            Pending pending;
            synchronized (this) {
                pending = latest.values().stream().filter(value -> !value.completion().isDone()).findFirst().orElse(null);
                if (pending == null) { writing = false; return; }
            }
            try { write(pending.snapshot(), false); pending.completion().complete(null); }
            catch (IOException | RuntimeException failure) { pending.completion().completeExceptionally(failure); }
        }
    }
    private void write(CatalogCodec.Snapshot snapshot, boolean migration) throws IOException {
        // 图纸与索引一起提交；不同机器共用同一结构时仍按内容指纹复用完整档案。
        String json = CatalogCodec.encode(snapshot);
        if (json.getBytes(StandardCharsets.UTF_8).length > CatalogLimits.FILE_BYTES) throw new IOException("catalog_checkpoint_too_large");
        var blueprints = new LinkedHashMap<String, String>();
        snapshot.blueprints().forEach(value -> blueprints.put(value.fingerprint(), value.blueprintJson()));
        database.write("machines", snapshot.identityKey(), json, blueprints, migration);
    }
    private String readBlueprint(String key, String fingerprint) {
        try {
            String json = LegacyMemoryFiles.read(blueprintPath(key, fingerprint), MachineBlueprint.MAX_BYTES);
            if (json == null) throw new IOException("machine_blueprint_archive_missing");
            return json;
        } catch (IOException failure) { throw new CompletionException("machine_blueprint_read_failed", failure); }
    }
    private Path blueprintPath(String key, String fingerprint) {
        path(key);
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid blueprint fingerprint");
        return directory.resolve(key + "-blueprints").resolve(fingerprint + ".json");
    }
    private Path path(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid catalog identity key");
        return directory.resolve(key + ".json");
    }
}
