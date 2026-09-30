// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 读取并保存客户端的 Dev 预览开关与 F9 调试面板可见性，文件是 config/maicraft-preview.properties；不修改服务器设置。
 */
public final class PreviewConfig {
    private static Path file;
    private static boolean enabled;
    private static boolean hudVisible;
    private PreviewConfig() {}

    // 没有配置、读坏或 devMode 不是 true 时默认关闭。状态保存在内存里，不会每次查询都重读文件。
    static void load(Path gameDirectory) {
        file = gameDirectory.resolve("config/maicraft-preview.properties");
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
            catch (IOException | IllegalArgumentException ignored) { enabled = false; hudVisible = false; return; }
        }
        enabled = Boolean.parseBoolean(values.getProperty("devMode", "false"));
        hudVisible = Boolean.parseBoolean(values.getProperty("debugHud", "false"));
    }

    static boolean enabled() {
        // 自动验收进程可显式选择是否人工审图，覆盖只作用于本次启动，不写回玩家的配置文件。
        String override = System.getProperty("maicraft.preview.enabled");
        if ("true".equalsIgnoreCase(override)) return true;
        if ("false".equalsIgnoreCase(override)) return false;
        return enabled;
    }
    public static boolean enabled(Path gameDirectory) {
        if (file == null) load(gameDirectory);
        return enabled();
    }

    static boolean hudVisible() { return hudVisible; }
    public static boolean hudVisible(Path gameDirectory) {
        if (file == null) load(gameDirectory);
        return hudVisible;
    }
    // 先改变本次运行的开关，再写文件。写入失败时调用方会提示，但本次运行的新值仍然生效。
    static void enabled(boolean value) throws IOException {
        enabled = value;
        persist();
    }
    /** F9 面板开关由 client.debug 包切换，与 Dev 开关共用一份配置文件。 */
    public static void hudVisible(boolean value) throws IOException {
        hudVisible = value;
        persist();
    }
    // 两个开关共用同一份文件，每次都整体重写，避免互相覆盖对方刚保存的值。
    private static void persist() throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("devMode", Boolean.toString(enabled));
        values.setProperty("debugHud", Boolean.toString(hudVisible));
        try (Writer writer = Files.newBufferedWriter(file)) {
            values.store(writer, "MaiCraft client debug; /maicraft dev on|off; F9 toggles the panel");
        }
    }
}
