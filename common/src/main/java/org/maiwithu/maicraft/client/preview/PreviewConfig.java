// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 读取并保存客户端的 Dev 预览开关，文件是 config/maicraft-preview.properties；不修改服务器设置。
 */
public final class PreviewConfig {
    private static Path file;
    private static boolean enabled;
    private PreviewConfig() {}

    // 没有配置、读坏或 devMode 不是 true 时默认关闭。状态保存在内存里，不会每次查询都重读文件。
    static void load(Path gameDirectory) {
        file = gameDirectory.resolve("config/maicraft-preview.properties");
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
            catch (IOException | IllegalArgumentException ignored) { enabled = false; return; }
        }
        enabled = Boolean.parseBoolean(values.getProperty("devMode", "false"));
    }

    static boolean enabled() { return enabled; }
    public static boolean enabled(Path gameDirectory) {
        if (file == null) load(gameDirectory);
        return enabled;
    }
    // 先改变本次运行的开关，再写文件。写入失败时调用方会提示，但本次运行的新值仍然生效。
    static void enabled(boolean value) throws IOException {
        enabled = value;
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("devMode", Boolean.toString(value));
        try (Writer writer = Files.newBufferedWriter(file)) {
            values.store(writer, "MaiCraft client blueprint review; /maicraft Dev on|off");
        }
    }
}
