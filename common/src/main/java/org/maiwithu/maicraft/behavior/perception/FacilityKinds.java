// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Set;

/**
 * 设施方块的分类：看见一个功能方块时，按它是什么决定怎么用、写不写进世界记忆、记成哪种。
 * 分类只认原版的功能方块；联动模组的机器归各自的能力认识，不在这里列。
 */
public final class FacilityKinds {

    /**
     * 能装东西的方块：看见只记"这里有只"，里面有没有货要开过才知道。
     * 潜影盒有十七种颜色（不染色的叫 shulker_box，染色的叫"颜色_shulker_box"），按后缀认。
     */
    public static final Set<String> CONTAINERS = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box",
            "minecraft:ender_chest");

    /** 用一次就知道能用的设施：工作台、熔炉这类。 */
    public static final Set<String> WORKSTATIONS = Set.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:smoker", "minecraft:anvil", "minecraft:grindstone", "minecraft:stonecutter",
            "minecraft:loom", "minecraft:cartography_table", "minecraft:smithing_table",
            "minecraft:brewing_stand");

    private FacilityKinds() {}

    /** 是能装东西的容器。 */
    public static boolean isContainer(String blockType) {
        return CONTAINERS.contains(blockType)
                || blockType.startsWith("minecraft:") && blockType.endsWith("_shulker_box");
    }

    /** 是工作设施。 */
    public static boolean isWorkstation(String blockType) {
        return WORKSTATIONS.contains(blockType);
    }

    /** 是床：睡的地方，做设施观察报告，但不往世界记忆里写——床认位置不认"见过"。 */
    public static boolean isBed(String blockType) {
        return blockType.endsWith("_bed");
    }
}
