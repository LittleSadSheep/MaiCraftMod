// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.maiwithu.maicraft.game.world.ReadsBlockTags;

/** 方块标签的替身：测试里按需要摆标签，不碰游戏注册表。vanilla() 预先摆好原版与两个加载器通用标签的真实成员。 */
public final class FakeBlockTags implements ReadsBlockTags {

    private final Map<String, Set<String>> members = new LinkedHashMap<>();

    /** 原版 1.21.1 与通用标签里和设施有关的成员：箱子、木桶、潜影盒、铁砧、工作台、熔炉。 */
    public static FakeBlockTags vanilla() {
        return new FakeBlockTags()
                .tag("c:chests", "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest")
                .tag("c:chests/ender", "minecraft:ender_chest")
                .tag("c:barrels", "minecraft:barrel")
                .tag("minecraft:shulker_boxes", "minecraft:shulker_box", "minecraft:red_shulker_box",
                        "minecraft:light_blue_shulker_box")
                .tag("minecraft:anvil", "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil")
                .tag("c:player_workstations/crafting_tables", "minecraft:crafting_table")
                .tag("c:player_workstations/furnaces", "minecraft:furnace");
    }

    /** 给一个标签加几种方块。 */
    public FakeBlockTags tag(String tag, String... blockTypes) {
        Set<String> blocks = members.computeIfAbsent(tag, ignored -> new LinkedHashSet<>());
        for (String blockType : blockTypes) {
            blocks.add(blockType);
        }
        return this;
    }

    @Override public Set<String> tagsOf(String blockType) {
        Set<String> tags = new LinkedHashSet<>();
        members.forEach((tag, blocks) -> {
            if (blocks.contains(blockType)) tags.add(tag);
        });
        return tags;
    }

    @Override public Set<String> blocksIn(String tag) {
        return Set.copyOf(members.getOrDefault(tag, Set.of()));
    }
}
