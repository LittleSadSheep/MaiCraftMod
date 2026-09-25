// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletionException;

/** 在后台有界读取 JSON，并合并请求执行原子保存；文件操作失败时保留上一份目录。 */
final class MachineCatalogStore {
    record Loaded(CatalogCodec.Snapshot snapshot, boolean needsSave) {}
    private record Pending(CatalogCodec.Snapshot snapshot, CompletableFuture<Void> completion) {}
    private final Path directory;
    private final Executor executor;
    private final Map<String, Pending> latest = new LinkedHashMap<>();
    private boolean writing;

    MachineCatalogStore(Path directory, Executor executor) { this.directory = directory.toAbsolutePath().normalize(); this.executor = executor; }
    CompletableFuture<Loaded> load(String key) {
        synchronized (this) {
            Pending captured = latest.get(key);
            if (captured != null) return CompletableFuture.completedFuture(new Loaded(captured.snapshot(),
                    !captured.completion().isDone() || captured.completion().isCompletedExceptionally()));
        }
        return CompletableFuture.supplyAsync(() -> {
            Path path = path(key);
            try {
                long size;
                try { size = Files.size(path); }
                catch (NoSuchFileException absent) { return new Loaded(new CatalogCodec.Snapshot(key, List.of(), List.of()), false); }
                if (size > CatalogLimits.FILE_BYTES) throw new IOException("catalog_file_too_large");
                byte[] bytes;
                try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(CatalogLimits.FILE_BYTES + 1); }
                if (bytes.length == 0 || bytes.length > CatalogLimits.FILE_BYTES) throw new IOException("catalog_file_size_invalid");
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return new Loaded(CatalogCodec.decode(json, key, fingerprint -> readBlueprint(key, fingerprint)), false);
            } catch (IOException | RuntimeException failure) { throw new CompletionException("catalog_load_failed", failure); }
        }, executor);
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
            try { write(pending.snapshot()); pending.completion().complete(null); }
            catch (IOException | RuntimeException failure) { pending.completion().completeExceptionally(failure); }
        }
    }
    private void write(CatalogCodec.Snapshot snapshot) throws IOException {
        // 先落地不可变蓝图再发布目录索引；中途写入失败时，上一份目录仍能找到原来的蓝图。
        for (var blueprint : snapshot.blueprints()) {
            Path target = blueprintPath(snapshot.identityKey(), blueprint.fingerprint());
            if (!Files.exists(target)) writeAtomic(target, blueprint.blueprintJson().getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = CatalogCodec.encode(snapshot).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CatalogLimits.FILE_BYTES) throw new IOException("catalog_checkpoint_too_large");
        writeAtomic(path(snapshot.identityKey()), bytes);
    }
    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { throw new IOException("catalog_atomic_move_unavailable", unavailable); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private String readBlueprint(String key, String fingerprint) {
        try (var input = Files.newInputStream(blueprintPath(key, fingerprint))) {
            byte[] bytes = input.readNBytes(MachineBlueprint.MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MachineBlueprint.MAX_BYTES) throw new IOException("machine_blueprint_archive_size");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
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
