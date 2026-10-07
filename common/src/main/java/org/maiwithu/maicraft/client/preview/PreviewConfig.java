// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 读取并保存客户端的 Dev 预览开关、F9 调试面板可见性、导航路线显示与 attention 事件流显示档位，
 * 文件是 config/maicraft-preview.properties；不修改服务器设置。
 */
public final class PreviewConfig {
    /** 调试面板 attention 事件流的显示档位：全部、只看最新一行、隐藏；只影响面板展示，MCP 感知照常产出。 */
    public enum AttentionFeedMode {
        ALL, LATEST, OFF;
        /** F9+A 的循环次序：全部 → 只看最新一行 → 隐藏 → 全部。 */
        public AttentionFeedMode next() {
            return this == ALL ? LATEST : this == LATEST ? OFF : ALL;
        }
    }

    private static Path file;
    private static boolean enabled;
    private static boolean hudVisible;
    private static boolean pathLines;
    private static AttentionFeedMode attentionFeed = AttentionFeedMode.ALL;
    private PreviewConfig() {}

    // 没有配置或读坏时布尔开关默认关闭，事件流档位默认全部显示。状态保存在内存里，不会每次查询都重读文件。
    static void load(Path gameDirectory) {
        file = gameDirectory.resolve("config/maicraft-preview.properties");
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
            catch (IOException | IllegalArgumentException ignored) {
                enabled = false; hudVisible = false; pathLines = false;
                attentionFeed = AttentionFeedMode.ALL; return;
            }
        }
        enabled = Boolean.parseBoolean(values.getProperty("devMode", "false"));
        hudVisible = Boolean.parseBoolean(values.getProperty("debugHud", "false"));
        pathLines = Boolean.parseBoolean(values.getProperty("pathLines", "false"));
        attentionFeed = parseFeedMode(values.getProperty("attentionFeed"));
    }

    /** 事件流档位解析：旧版布尔迁移（true=全部、false=隐藏），latest 为新增档，无法识别或缺键回退全部。 */
    private static AttentionFeedMode parseFeedMode(String raw) {
        if (raw == null) return AttentionFeedMode.ALL;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "all" -> AttentionFeedMode.ALL;
            case "latest" -> AttentionFeedMode.LATEST;
            case "false", "off" -> AttentionFeedMode.OFF;
            default -> AttentionFeedMode.ALL;
        };
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

    /** 导航路线显示是 F9+P 的独立开关，与 Dev 分离；Dev 开启时始终画线，不读此键。 */
    public static boolean pathLines(Path gameDirectory) {
        if (file == null) load(gameDirectory);
        return pathLines;
    }

    static boolean pathLines() { return pathLines; }

    public static void pathLines(boolean value) throws IOException {
        pathLines = value;
        persist();
    }

    /** attention 事件流显示档位是 F9+A 的三档循环开关，只影响调试面板下方的事件区。 */
    public static AttentionFeedMode attentionFeed(Path gameDirectory) {
        if (file == null) load(gameDirectory);
        return attentionFeed;
    }

    static AttentionFeedMode attentionFeed() { return attentionFeed; }

    public static void attentionFeed(AttentionFeedMode value) throws IOException {
        attentionFeed = value;
        persist();
    }

    // 各开关共用同一份文件，每次都整体重写，避免互相覆盖对方刚保存的值。
    private static void persist() throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("devMode", Boolean.toString(enabled));
        values.setProperty("debugHud", Boolean.toString(hudVisible));
        values.setProperty("pathLines", Boolean.toString(pathLines));
        values.setProperty("attentionFeed", attentionFeed.name().toLowerCase(Locale.ROOT));
        try (Writer writer = Files.newBufferedWriter(file)) {
            values.store(writer,
                    "MaiCraft client debug; /maicraft dev on|off; F9 panel; F9+H task list; F9+P path lines; F9+A attention feed (all/latest/off)");
        }
    }
}
