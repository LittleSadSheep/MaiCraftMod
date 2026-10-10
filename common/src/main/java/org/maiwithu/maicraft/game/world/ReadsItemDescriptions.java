// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 读一件物品"玩家看得到的说明"的只读接缝：名字、鼠标悬停的说明、按住 Shift 与 Ctrl 看到的用法、
 * 它是方块时的方块状态。文字都按当前游戏语言读，和玩家看到的一样；测试里用替身摆。
 */
public interface ReadsItemDescriptions {

    /** 这件物品的说明（物品 ID，例如 create:mechanical_mixer）；注册表里没有这件物品时为空。 */
    Optional<ItemDescription> describe(String itemId);

    /** 当前语言里名字或 ID 对得上全部关键词的物品 ID，按注册表顺序；关键词已经是小写。 */
    List<String> search(List<String> terms);

    /** 一件物品当前语言里的名字；注册表里没有时用 ID。 */
    String nameOf(String itemId);

    /**
     * 一件物品玩家看得到的说明。
     *
     * @param id              物品 ID
     * @param name            当前语言里的名字
     * @param blockId         它放下去是哪种方块；不是方块物品时为 null
     * @param blockProperties 那种方块的方块状态：每个属性的名字、默认值、全部取值
     * @param tooltip         鼠标悬停时名字下面的那几行
     * @param usageSummary    按住 Shift 看到的摘要；模组没写时为空串
     * @param usage           按住 Shift 看到的"条件 → 效果"，逐条
     * @param controls        按住 Ctrl 看到的"操作 → 效果"，逐条
     */
    record ItemDescription(String id, String name, String blockId, List<BlockProperty> blockProperties,
            List<String> tooltip, String usageSummary, List<UsageLine> usage, List<UsageLine> controls) {
        public ItemDescription {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            blockProperties = List.copyOf(blockProperties);
            tooltip = List.copyOf(tooltip);
            usageSummary = usageSummary == null ? "" : usageSummary;
            usage = List.copyOf(usage);
            controls = List.copyOf(controls);
        }
    }

    /** 方块状态的一个属性：名字、默认值、全部取值。 */
    record BlockProperty(String name, String defaultValue, List<String> values) {
        public BlockProperty {
            values = List.copyOf(values);
        }
    }

    /** 按住 Shift 或 Ctrl 看到的一条：什么时候（或做什么），会怎样。 */
    record UsageLine(String when, String result) {}
}
