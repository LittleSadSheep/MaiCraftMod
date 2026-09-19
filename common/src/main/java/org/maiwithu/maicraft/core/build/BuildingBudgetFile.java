// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import org.maiwithu.maicraft.core.Constants;

/** 首次进入新版客户端时写出带中文用途说明的预算文件，已有文件始终由服主或玩家管理。 */
final class BuildingBudgetFile {
    private BuildingBudgetFile() {}

    static BuildingBudgets load(Path file) {
        BuildingBudgets result;
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                // 只创建缺失文件，其他进程刚写入配置时改为读取它，不能覆盖用户已经填写的建造限额。
                try { Files.writeString(file, defaultsText(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
                catch (FileAlreadyExistsException alreadyCreated) { /* 继续读取已存在的配置。 */ }
            }
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            result = BuildingBudgets.fromProperties(properties);
        } catch (IOException | IllegalArgumentException failure) {
            // 配置文件不可读时允许玩家进入游戏，以默认预算工作，同时明确报告读取问题。
            result = BuildingBudgets.defaults().withDiagnostic(new BuildingBudgets.Diagnostic(
                    "configuration", file.toString(), "建筑预算文件读取失败，已使用默认值：" + failure.getMessage()));
        }
        for (var issue : result.diagnostics()) Constants.LOG.warn("[building-budget] {}: {}", issue.key(), issue.message());
        return result;
    }

    private static String defaultsText() {
        StringBuilder text = new StringBuilder("# MaiCraft 建筑与蓝图资源预算；修改后重启客户端生效。\n"
                + "# 数量、字节和格数填写正整数；毫秒可填写正小数。非法项单独退回默认值并写入日志。\n"
                + "# 空气目标仍参与最终核验。提高规模不会自动开始施工或授予创造物品。\n"
                + "# 单次完整展开仍会占用内存；这些配置不等同于已经实现分区流式大工程。\n\n");
        for (var key : BuildingBudgets.Key.values())
            text.append("# ").append(key.description).append('\n').append(key.property).append('=')
                    .append(key.fallback).append("\n\n");
        return text.toString();
    }
}
