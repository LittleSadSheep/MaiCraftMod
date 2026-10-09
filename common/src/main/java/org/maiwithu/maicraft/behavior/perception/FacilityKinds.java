// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.game.world.ReadsBlockTags;

/**
 * 设施方块的分类：看见一个功能方块时，按它是什么决定怎么用、写不写进世界记忆、记成哪种。
 *
 * <p>容器与工作设施按方块标签认，不按方块 ID 名单：染色的潜影盒、开裂和损坏的铁砧、
 * 进了通用箱子标签的模组箱子，都和原版的一样算。联动模组的机器归各自的能力认识，不在这里。
 * 末影箱不算容器：它打开的是玩家自己的私人存储，换个玩家里面的东西都不一样，
 * 不能当世界里的箱子去翻，也不能记成某个位置上放着的箱子。
 */
public final class FacilityKinds {

    /** 容器的标签：通用的箱子与木桶标签两个加载器都有；潜影盒只有原版标签（通用的两边都没有）。 */
    static final List<String> CONTAINER_TAGS = List.of("c:chests", "c:barrels", "minecraft:shulker_boxes");

    /** 末影箱的标签与原版 ID：挂着它的即使也挂着箱子标签，也不算容器。 */
    static final String ENDER_CHEST_TAG = "c:chests/ender";
    static final String ENDER_CHEST = "minecraft:ender_chest";

    /** 原版按 ID 认的工作设施：用一次就知道能用的台子。 */
    static final Set<String> WORKSTATIONS = Set.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:smoker", "minecraft:anvil", "minecraft:grindstone", "minecraft:stonecutter",
            "minecraft:loom", "minecraft:cartography_table", "minecraft:smithing_table",
            "minecraft:brewing_stand");

    /** 按标签认的工作设施：铁砧标签把开裂、损坏的也算进来；通用的工作台、熔炉标签让进了标签的模组台子也算。 */
    static final List<String> WORKSTATION_TAGS = List.of(
            "minecraft:anvil", "c:player_workstations/crafting_tables", "c:player_workstations/furnaces");

    private final ReadsBlockTags tags;

    public FacilityKinds(ReadsBlockTags tags) {
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    /** 是能装东西的容器：挂着箱子、木桶或潜影盒标签，且不是末影箱。 */
    public boolean isContainer(String blockType) {
        if (blockType.equals(ENDER_CHEST)) {
            return false;
        }
        Set<String> blockTags = tags.tagsOf(blockType);
        if (blockTags.contains(ENDER_CHEST_TAG)) {
            return false;
        }
        return CONTAINER_TAGS.stream().anyMatch(blockTags::contains);
    }

    /** 是工作设施：原版 ID 表里的，或挂着铁砧、工作台、熔炉标签的。 */
    public boolean isWorkstation(String blockType) {
        if (WORKSTATIONS.contains(blockType)) {
            return true;
        }
        Set<String> blockTags = tags.tagsOf(blockType);
        return WORKSTATION_TAGS.stream().anyMatch(blockTags::contains);
    }

    /** 是床：睡的地方，做设施观察报告，但不往世界记忆里写——床认位置不认"见过"。 */
    public static boolean isBed(String blockType) {
        return blockType.endsWith("_bed");
    }

    /** 现场扫描要登记的全部容器方块类型：几个容器标签下的方块，去掉末影箱。 */
    public Set<String> containerBlockTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (String tag : CONTAINER_TAGS) {
            types.addAll(tags.blocksIn(tag));
        }
        types.remove(ENDER_CHEST);
        types.removeAll(tags.blocksIn(ENDER_CHEST_TAG));
        return types;
    }

    /** 现场扫描要登记的全部工作设施方块类型：ID 表里的加上几个工作设施标签下的方块。 */
    public Set<String> workstationBlockTypes() {
        Set<String> types = new LinkedHashSet<>(WORKSTATIONS);
        for (String tag : WORKSTATION_TAGS) {
            types.addAll(tags.blocksIn(tag));
        }
        return types;
    }
}
