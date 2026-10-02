// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeDocument;

// 检查提示中的显示标记、连续编号、操作名称和语言缓存；长说明也必须保留末尾操作条件。
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
        check(!oversized.truncated() && oversized.summary().length() == 40000 && oversized.behaviours().size() == 1,
                "long descriptions retain the following operating conditions");
        // 物品作者提供较多连续规则时，编号超过旧上限的操作也应交给设计者。
        for (int index = 1; index <= 105; index++) {
            translations.put(key + ".condition" + index, "条件" + index);
            translations.put(key + ".behaviour" + index, "行为" + index);
        }
        check(CreateTooltipDescription.read(key, translations::get).behaviours().size() == 105,
                "all native consecutive conditions are read");
        // 较长的原生物品提示也完整交付，读取异常则明确未知，不能伪装成没有用法限制。
        var tooltip = CreateTooltipKnowledge.readTooltip(lines -> {
            for (int index = 0; index < 40; index++) lines.add(Component.literal(index + "：" + "说明".repeat(600)));
        });
        check(tooltip.status().equals("available") && tooltip.lines().size() == 40
                && tooltip.lines().getLast().length() > 1024, "native tooltip lines and tails are retained");
        check(CreateTooltipKnowledge.readTooltip(lines -> { throw new IllegalStateException("needs a world"); })
                .status().equals("api_unavailable"),
                "unreadable native descriptions remain unknown");
        System.out.println("CreateTooltipKnowledgeTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
