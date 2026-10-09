// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 面板的设置：开到哪一档、导航路线开没开。存在 config/maicraft-debug.properties，重启后照旧；
 * 页不存，每次打开都从"此刻"开始。第一次装上默认是关，免得直播画面里突然多出一块。
 */
public final class PanelSettings {
    private static final Logger LOG = LoggerFactory.getLogger(PanelSettings.class);
    static final String FILE_NAME = "maicraft-debug.properties";

    private final Path file;
    private PanelLevel level = PanelLevel.OFF;
    private boolean pathLines;

    /** @param configDirectory 游戏的 config 目录 */
    public PanelSettings(Path configDirectory) {
        this.file = configDirectory.resolve(FILE_NAME);
        load();
    }

    /** 开到哪一档。 */
    public PanelLevel level() {
        return level;
    }

    /** 导航路线开没开。 */
    public boolean pathLines() {
        return pathLines;
    }

    /** 换一档并存盘；存不下时这一次照样换，抛出原因让调用方在动作栏说一声。 */
    void level(PanelLevel value) throws IOException {
        level = value;
        save();
    }

    /** 开关导航路线并存盘；存不下时这一次照样生效。 */
    void pathLines(boolean value) throws IOException {
        pathLines = value;
        save();
    }

    // 读不到或写坏了的文件按默认（关着、路线不画）处理，并记一行日志，不让面板设置挡住游戏启动。
    private void load() {
        if (!Files.exists(file)) return;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException | IllegalArgumentException failure) {
            LOG.warn("调试面板设置读不出来，按默认处理：{}", file, failure);
            return;
        }
        level = PanelLevel.fromSetting(values.getProperty("level", "off"));
        pathLines = Boolean.parseBoolean(values.getProperty("path_lines", "false"));
    }

    private void save() throws IOException {
        Properties values = new Properties();
        values.setProperty("level", level.settingValue());
        values.setProperty("path_lines", Boolean.toString(pathLines));
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            values.store(writer, "MaiCraft debug panel");
        }
    }
}
