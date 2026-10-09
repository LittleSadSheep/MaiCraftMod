// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实例配置：文件不在生成默认，开关读得出来，坏文件按默认值收场不崩。 */
class InstanceConfigTest {

    @TempDir
    Path configDir;

    @Test
    void missingFileGetsDefaultContentAndClosedSwitch() throws IOException {
        Path file = configDir.resolve("maicraft.json");
        InstanceConfig config = InstanceConfig.read(configDir);
        assertFalse(config.allowGameCommands(), "默认不允许执行游戏命令");
        // 默认文件要真的写出来，所有者照着改才有样子。
        assertTrue(Files.exists(file), "文件不存在时应生成默认配置文件");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("allowGameCommands"));
    }

    @Test
    void switchReadFromFile() throws IOException {
        Files.writeString(configDir.resolve("maicraft.json"),
                "{\"allowGameCommands\": true}", StandardCharsets.UTF_8);
        assertTrue(InstanceConfig.read(configDir).allowGameCommands());

        Files.writeString(configDir.resolve("maicraft.json"),
                "{\"allowGameCommands\": false}", StandardCharsets.UTF_8);
        assertFalse(InstanceConfig.read(configDir).allowGameCommands());
    }

    @Test
    void brokenFileFallsBackToDefaults() throws IOException {
        // 不是 JSON：按默认值收场，不抛出去挡启动。
        Files.writeString(configDir.resolve("maicraft.json"), "这不是 JSON", StandardCharsets.UTF_8);
        assertFalse(InstanceConfig.read(configDir).allowGameCommands());

        // 键值类型不对：同样按默认值。
        Files.writeString(configDir.resolve("maicraft.json"),
                "{\"allowGameCommands\": \"开\"}", StandardCharsets.UTF_8);
        assertFalse(InstanceConfig.read(configDir).allowGameCommands());
    }

    @Test
    void missingKeyMeansClosed() throws IOException {
        Files.writeString(configDir.resolve("maicraft.json"), "{}", StandardCharsets.UTF_8);
        assertFalse(InstanceConfig.read(configDir).allowGameCommands(), "缺键按关闭，不算坏文件");
    }
}
