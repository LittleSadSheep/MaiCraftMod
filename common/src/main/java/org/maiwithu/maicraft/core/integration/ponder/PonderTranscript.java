// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.List;
import java.util.Map;

/**
 * 保存提取出的教程文字与提示，按四十条一页展示；累计等待只是演示节奏，不是机器生产速度。
 */
public record PonderTranscript(String sceneId, String title, List<Step> steps,
                               Map<String, Integer> unexpanded, List<String> warnings) {
    public record Step(int ordinal, int afterDelayTicks, String kind, String text, String focus) {}
    public PonderTranscript {
        steps = List.copyOf(steps); unexpanded = Map.copyOf(unexpanded); warnings = List.copyOf(warnings);
    }

    public String markdown(PonderAccess.Entry entry, String baseUri, int offset) {
        if (offset < 0 || offset > steps.size() || offset > 0 && offset == steps.size())
            throw new IllegalArgumentException("Ponder text offset is outside this scene");
        int end = Math.min(steps.size(), offset + 40);
        StringBuilder text = new StringBuilder("# ").append(title).append("\n\n")
                .append("- 组件：`").append(entry.component()).append("`\n")
                .append("- 场景：`").append(sceneId).append("`；演示结构：`").append(entry.schematic()).append("`\n")
                .append("- 来源：当前注册的 Ponder 故事板；说明按游戏语言读取，缺失时回退作者原文。\n")
                .append("- 提取方式：只编译说明，不播放、不 tick、不创建演示世界，也不修改玩家世界。\n\n")
                .append("## 原始说明与操作提示\n\n");
        for (int i = offset; i < end; i++) {
            Step step = steps.get(i);
            text.append(i + 1).append(". **").append(step.kind()).append("**：")
                    .append(step.text().replace("\n", "\n   "));
            if (step.focus() != null) text.append("〔演示焦点 ").append(step.focus()).append("〕");
            text.append("〔显式等待累计 ").append(step.afterDelayTicks()).append(" tick〕\n");
        }
        if (steps.isEmpty()) text.append("该故事板未提取到可读旁白或控制提示。\n");
        if (end < steps.size()) text.append("\n[继续读取第 ").append(end + 1).append(" 条起的内容](")
                .append(baseUri).append("?offset=").append(end).append(")\n");
        text.append("\n## 解释边界\n\n")
                .append("以上是作者演示证据。焦点来自未加载演示结构的编译过程，可能受占位边界影响，只用于定位旁白；等待刻数不是生产速率。")
                .append("持物提示只列 ID 与数量，物品数据组件未展开。")
                .append("未显示的规则保持未知。机器是否可操作，还需查询当前 MaiCraft abilities 与实际世界状态。\n");
        if (!unexpanded.isEmpty()) {
            text.append("\n未执行、未推断含义的演示指令：\n");
            unexpanded.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row ->
                    text.append("- `").append(row.getKey()).append("` × ").append(row.getValue()).append("\n"));
        }
        if (!warnings.isEmpty()) {
            text.append("\n提取提示：\n"); warnings.forEach(warning -> text.append("- ").append(warning).append("\n"));
        }
        return text.append("\n[读取方式](maicraft://knowledge/guide)\n").toString();
    }
}
