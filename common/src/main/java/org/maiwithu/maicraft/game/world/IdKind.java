// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

/**
 * 注册 ID 的种类：参数里写的 ID 是哪一类东西，报错和目录页里怎么称呼它。
 * 物品、方块、实体类型在游戏内置的注册表里，不进世界也读得到；群系与结构在数据包注册表里，要进了世界才有。
 */
public enum IdKind {
    ITEM("物品"),
    BLOCK("方块"),
    ENTITY_TYPE("实体"),
    BIOME("群系"),
    STRUCTURE("结构");

    private final String label;

    IdKind(String label) {
        this.label = label;
    }

    /** 报错里的叫法，例如"物品 minecraft:diamon 不存在"的"物品"。 */
    public String label() {
        return label;
    }
}
