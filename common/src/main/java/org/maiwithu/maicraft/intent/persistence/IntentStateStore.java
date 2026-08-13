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
import org.maiwithu.maicraft.core.Constants;

/** Bounded, version-checked, atomic JSON storage for one game-derived identity. */
public final class IntentStateStore {
    public static final int VERSION = 1;
    public static final long MAX_BYTES = 4L * 1024L * 1024L;

    public enum Status { ABSENT, LOADED, CORRUPT }

    public record LoadResult(Status status, JsonObject root) {}

    public LoadResult load(StateIdentity identity) {
        Path file = file(identity);
        if (!Files.exists(file)) return new LoadResult(Status.ABSENT, new JsonObject());
        try {
            long bytes = Files.size(file);
            if (bytes <= 0L || bytes > MAX_BYTES) {
                throw new IOException("semantic state size is outside bounds");
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
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

    public void save(StateIdentity identity, JsonObject root) throws IOException {
        byte[] payload = root.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_BYTES) {
            throw new IOException("semantic state exceeds " + MAX_BYTES + " bytes");
        }
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
