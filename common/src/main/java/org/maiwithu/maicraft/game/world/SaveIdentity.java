// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

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
 * 当前进度属于哪个存档或服务器：单人世界按存档目录认，多人世界按服务器地址认，
 * 算成一串固定长度的编号，文件名里不直接写出地址或路径。还没进世界时不凭外部传来的文字猜，返回空。
 *
 * @param key       从存档路径或服务器地址算出的 SHA-256 编号
 * @param directory 本游戏实例存放 MaiCraft 进度数据的目录
 */
public record SaveIdentity(String key, Path directory) {

    /** 从当前游戏状态认出世界：优先认单人存档的存档目录，认不到再看多人服务器的地址。 */
    public static Optional<SaveIdentity> current(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return Optional.empty();
        }
        String raw = singleplayerIdentity(minecraft);
        if (raw == null) raw = multiplayerIdentity(minecraft);
        if (raw == null) return Optional.empty();
        // 进度数据放在 config/maicraft/state 里，与外部工具放在 config 根下的数据文件互不干扰。
        Path directory = minecraft.gameDirectory.toPath().resolve("config").resolve("maicraft").resolve("state");
        return Optional.of(new SaveIdentity(sha256(raw), directory));
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
