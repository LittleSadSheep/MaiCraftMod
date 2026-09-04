// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.maiwithu.maicraft.core.Constants;

/** Bounded semantic checkpoints with one serial background writer and atomic file replacement. */
public final class IntentStateStore {
    public static final int VERSION = 1;
    public static final long MAX_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_RESIDENT_IDENTITIES = 8;

    // A bounded mailbox, not an executor queue: repeated saves replace the same identity's
    // pending snapshot. No task, world or caller-owned JsonObject is retained by the worker.
    private final Map<StateIdentity, PendingSave> latest = new LinkedHashMap<>();
    private final Executor writerExecutor;
    private boolean workerRunning;

    public IntentStateStore() {
        this(command -> {
            Thread worker = new Thread(command, "maicraft-state-writer");
            worker.setDaemon(true);
            worker.start();
        });
    }

    IntentStateStore(Executor writerExecutor) {
        this.writerExecutor = java.util.Objects.requireNonNull(writerExecutor);
    }

    public enum Status { ABSENT, LOADED, CORRUPT }

    public record LoadResult(Status status, JsonObject root) {}

    /**
     * One-time connection restore. Resident checkpoints need no IO; a cold restore reads at most
     * four MiB, including protection against a file growing after its size was checked.
     */
    public LoadResult load(StateIdentity identity) {
        String captured;
        synchronized (latest) {
            PendingSave save = latest.get(identity);
            captured = save == null ? null : save.json;
        }
        // Respawn/reconnect must see the accepted checkpoint even if the disk is still busy.
        if (captured != null) return new LoadResult(Status.LOADED,
                JsonParser.parseString(captured).getAsJsonObject());
        Path file = file(identity);
        if (!Files.exists(file)) return new LoadResult(Status.ABSENT, new JsonObject());
        try {
            long bytes = Files.size(file);
            if (bytes <= 0L || bytes > MAX_BYTES) {
                throw new IOException("semantic state size is outside bounds");
            }
            byte[] payload;
            try (var input = Files.newInputStream(file)) {
                payload = input.readNBytes((int) MAX_BYTES + 1);
            }
            if (payload.length == 0 || payload.length > MAX_BYTES) {
                throw new IOException("semantic state size is outside bounds");
            }
            String json = new String(payload, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != VERSION) {
                throw new IOException("unsupported semantic state version");
            }
            if (!root.has("identity_key")
                    || !identity.key().equals(root.get("identity_key").getAsString())) {
                throw new IOException("semantic state identity mismatch");
            }
            return new LoadResult(Status.LOADED, root);
        } catch (RuntimeException | IOException invalid) {
            quarantine(file);
            Constants.LOG.warn("MaiCraft semantic state was invalid and quarantined ({})",
                    invalid.getClass().getSimpleName());
            return new LoadResult(Status.CORRUPT, new JsonObject());
        }
    }

    /**
     * Capture immutable JSON on the client thread, then return without waiting on storage.
     * A superseded pending save is cancelled; its replacement remains the authoritative checkpoint.
     */
    public CompletableFuture<Void> saveAsync(StateIdentity identity, JsonObject root) throws IOException {
        String json = boundedJson(root);
        PendingSave save = new PendingSave(identity, json);
        synchronized (latest) {
            if (!latest.containsKey(identity) && latest.size() >= MAX_RESIDENT_IDENTITIES) {
                var iterator = latest.entrySet().iterator();
                while (iterator.hasNext()) {
                    PendingSave candidate = iterator.next().getValue();
                    if (candidate.completion.isDone() && !candidate.completion.isCompletedExceptionally()) {
                        iterator.remove();
                        break;
                    }
                }
                if (latest.size() >= MAX_RESIDENT_IDENTITIES) {
                    throw new RejectedExecutionException("semantic state writer has too many pending identities");
                }
            }
            PendingSave replaced = latest.put(identity, save);
            if (replaced != null && !replaced.completion.isDone()) replaced.completion.cancel(false);
            if (!workerRunning) {
                workerRunning = true;
                try {
                    writerExecutor.execute(this::writePending);
                } catch (RuntimeException rejected) {
                    workerRunning = false;
                    save.completion.completeExceptionally(rejected);
                }
            }
        }
        return save.completion;
    }

    public boolean hasSnapshot(StateIdentity identity) {
        synchronized (latest) { return latest.containsKey(identity); }
    }

    /** Failed writes are retried by the ordinary save interval, never by a tight disk-error loop. */
    public boolean hasFailedSave(StateIdentity identity) {
        synchronized (latest) {
            PendingSave save = latest.get(identity);
            return save != null && save.completion.isCompletedExceptionally();
        }
    }

    private void writePending() {
        while (true) {
            PendingSave save = null;
            synchronized (latest) {
                for (PendingSave candidate : latest.values()) {
                    if (candidate.pending) {
                        candidate.pending = false;
                        save = candidate;
                        break;
                    }
                }
                if (save == null) {
                    workerRunning = false;
                    return;
                }
            }
            try {
                write(save.identity, save.json.getBytes(StandardCharsets.UTF_8));
                save.completion.complete(null);
            } catch (IOException | RuntimeException failure) {
                save.completion.completeExceptionally(failure);
                Constants.LOG.warn("Could not write MaiCraft semantic checkpoint ({})",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private static String boundedJson(JsonObject root) throws IOException {
        String json = root.toString();
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("semantic state exceeds " + MAX_BYTES + " bytes");
        }
        return json;
    }

    private static void write(StateIdentity identity, byte[] payload) throws IOException {
        Files.createDirectories(identity.directory());
        Path destination = file(identity);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.write(temporary, payload);
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class PendingSave {
        private final StateIdentity identity;
        private final String json;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private boolean pending = true;

        private PendingSave(StateIdentity identity, String json) {
            this.identity = identity;
            this.json = json;
        }
    }

    /** Quarantine a structurally valid JSON file whose semantic model failed validation. */
    public void quarantine(StateIdentity identity) {
        quarantine(file(identity));
    }

    private static Path file(StateIdentity identity) {
        return identity.directory().resolve(identity.key() + ".json");
    }

    private static void quarantine(Path file) {
        if (!Files.exists(file)) return;
        String stamp = Long.toString(Instant.now().toEpochMilli());
        Path corrupt = file.resolveSibling(file.getFileName() + "." + stamp + ".corrupt");
        try {
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException quarantineFailure) {
            Constants.LOG.warn("Could not quarantine invalid MaiCraft semantic state",
                    quarantineFailure);
        }
    }
}
