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
 * 决定任务进度属于哪个存档或服务器，避免连到另一处时直接使用上一次的任务和地标。
 *
 * @param key 从存档路径或服务器地址计算出的哈希；文件名中不直接写出地址或路径。
 * @param directory 当前游戏目录里的任务状态保存目录。
 */
public record StateIdentity(String key, Path directory) {
    public StateIdentity {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("state identity key must be SHA-256 hex");
        }
        directory = directory.toAbsolutePath().normalize();
    }

    public static Optional<StateIdentity> resolve(Minecraft minecraft) {
        // 优先识别单人存档，识别不到再看多人服务器；没进世界时不凭 MCP 传来的文字猜身份。
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
        // 用存档目录区分单人世界；Windows 路径不区分大小写，因此先统一成小写再计算。
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
        // 多人世界目前只按服务器地址区分，未包含玩家账号，也无法区分同一地址更换前后的世界。
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return "multiplayer\n" + server.ip.strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        // 将路径或地址变成固定长度编号；这只用于区分文件，不证明服务器内容没有变化。
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
