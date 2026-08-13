// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A game-derived persistence boundary. No MCP argument participates in this identity.
 *
 * @param key SHA-256 only; raw addresses and save paths never become filenames or public events
 * @param directory shared state directory under the current game directory
 */
public record StateIdentity(String key, Path directory) {
    public StateIdentity {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("state identity key must be SHA-256 hex");
        }
        directory = directory.toAbsolutePath().normalize();
    }

    public static Optional<StateIdentity> resolve(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return Optional.empty();
        }
        String raw = singleplayerIdentity(minecraft);
        if (raw == null) raw = multiplayerIdentity(minecraft);
        if (raw == null) return Optional.empty();
        Path directory = minecraft.gameDirectory.toPath()
                .resolve("config").resolve("maicraft").resolve("state");
        return Optional.of(new StateIdentity(sha256(raw), directory));
    }

    private static String singleplayerIdentity(Minecraft minecraft) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) return null;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            String stable = root.toString();
            if (isWindows()) stable = stable.toLowerCase(Locale.ROOT);
            return "singleplayer\n" + stable;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static String multiplayerIdentity(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return "multiplayer\n" + server.ip.strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
