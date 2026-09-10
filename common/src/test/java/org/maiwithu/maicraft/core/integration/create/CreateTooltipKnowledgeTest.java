// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.HashMap;
import java.util.Map;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeDocument;

// 检查提示中的显示标记、连续编号、操作名称、搜索文字和语言缓存更新；内容过长或配对说明缺失时应明确表示。
public final class CreateTooltipKnowledgeTest {
    public static void main(String[] args) {
        String key = "block.create.shared_tooltip.tooltip";
        Map<String, String> translations = new HashMap<>(Map.of(
                key + ".summary", "模拟_玩家_交互。",
                key + ".condition1", "提供树苗时",
                key + ".behaviour1", "能够_自动补种_。",
                key + ".control1", "按住 CTRL_KEY 右键",
                key + ".action1", "设置过滤条件。",
                key + ".condition3", "不应跨越缺失的编号",
                key + ".behaviour3", "忽略"));
        var description = CreateTooltipDescription.read(key, translations::get);
        check(description.summary().equals("模拟玩家交互。"), "Create highlight markup becomes plain text");
        check(description.behaviours().size() == 1, "numbering stops at a gap, matching Create");
        check(description.controls().getFirst().condition().contains("CTRL_KEY"), "literal heading preserves underscores");
        check(description.markdown().contains(key) && description.markdown().contains("自动补种"), "source and behavior remain readable");
        var entry = new KnowledgeDocument.Entry("maicraft://knowledge/block/create/deployer", "deployer", "机械手",
                "用法", description.searchText());
        check(entry.searchable().contains("自动补种") && entry.searchable().contains("过滤条件"), "usage search finds behavior and controls without exact block ID");

        var cache = new CreateTooltipDescription.Cache();
        Object language = new Object();
        check(cache.read(language, key, translations::get).summary().equals("模拟玩家交互。"), "cache first read");
        translations.put(key + ".summary", "新资源包说明");
        check(cache.read(language, key, unused -> { throw new AssertionError("cache hit must not reparse"); }) != null,
                "same resource generation reuses description");
        check(cache.read(new Object(), key, translations::get).summary().equals("新资源包说明"),
                "language replacement invalidates cache even when selected locale name is unchanged");
        check(CreateTooltipDescription.read("missing", translations::get).isEmpty(), "absent Create or description is harmless");
        translations.remove(key + ".action1");
        check(CreateTooltipDescription.read(key, translations::get).controls().getFirst().explanation().contains("未提供"),
                "missing paired text is explicit, not a fabricated function");
        translations.put(key + ".summary", "长".repeat(40000));
        var oversized = CreateTooltipDescription.read(key, translations::get);
        check(oversized.truncated() && oversized.summary().length() == 32768 && oversized.behaviours().isEmpty(),
                "oversized language entries have a bounded extraction budget");
        check(oversized.markdown().contains("部分内容未收录"), "truncation is disclosed");
        System.out.println("CreateTooltipKnowledgeTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
