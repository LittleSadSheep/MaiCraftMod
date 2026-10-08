// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.storage;

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
 * 世界身份：判定当前进度属于哪个存档或服务器，连到另一个世界时不把上一个世界的数据当自己的用。
 *
 * @param key 从存档路径或服务器地址算出的哈希；文件名里不直接写出地址或路径。
 * @param directory 本世界数据所在目录。
 * @param databaseFile 同一游戏实例共用的 SQLite 库文件。
 * @param scope 库内的范围名，区分不同用途的数据，避免共库后互相覆盖。
 */
public record StateIdentity(String key, Path directory, Path databaseFile, String scope) {
    /** 独立使用或测试夹具：直接在给定目录建库。 */
    public StateIdentity(String key, Path directory) {
        this(key, directory, directory.resolveSibling(DocumentStore.FILE_NAME), "state");
    }

    public StateIdentity {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("world identity key must be SHA-256 hex");
        }
        directory = directory.toAbsolutePath().normalize();
        databaseFile = databaseFile.toAbsolutePath().normalize();
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("empty storage scope");
    }

    /**
     * 从当前游戏状态识别世界身份：优先认单人存档的存档目录，认不到再看多人服务器的地址；
     * 还没进世界时不凭外部传来的文字猜身份，返回空。
     */
    public static Optional<StateIdentity> resolve(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return Optional.empty();
        }
        String raw = singleplayerIdentity(minecraft);
        if (raw == null) raw = multiplayerIdentity(minecraft);
        if (raw == null) return Optional.empty();
        // 数据放在自己的版本目录下，不与旧版本的数据文件混用，互不读到对方的数据。
        Path directory = minecraft.gameDirectory.toPath()
                .resolve("config").resolve("maicraft").resolve("v1").resolve("state");
        return Optional.of(new StateIdentity(sha256(raw), directory,
                directory.resolveSibling(DocumentStore.FILE_NAME), "state"));
    }

    /** 单人世界按存档目录区分；Windows 路径不区分大小写，先统一成小写再算，同一存档才算同一个世界。 */
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

    /** 多人世界按服务器地址区分；同一地址上换地图时仍算同一个身份，地址里也不含玩家账号。 */
    private static String multiplayerIdentity(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return "multiplayer\n" + server.ip.strip().toLowerCase(Locale.ROOT);
    }

    /** 把路径或地址变成固定长度编号；这只用于区分文件，不代表服务器内容没有变化。 */
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
