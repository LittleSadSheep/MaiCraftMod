// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.List;

/**
 * 读本实例注册了哪些 ID 的只读接缝：物品、方块、实体类型、群系、结构各有哪些注册 ID 与标签（含模组和数据包加的）。
 * ID 写错时报错末尾的"你是不是要"从这里挑候选，lookup 的群系与结构目录页也读它；实现在本包，测试用替身摆名单。
 */
public interface ReadsRegisteredIds {

    /** 这一类本实例注册过的全部 ID（命名空间:名字），按 ID 排序；读不到时给空（群系与结构要进了世界才有）。 */
    List<String> ids(IdKind kind);

    /** 这一类本实例里至少挂着一样东西的标签 ID（不带 #），按 ID 排序；读不到时给空。 */
    List<String> tags(IdKind kind);

    /**
     * 写错的 ID 或 # 标签的统一说法："物品 minecraft:diamon 不存在；你是不是要 minecraft:diamond"，
     * 标签说成"物品标签 #… 不存在"；本实例里找不到相近的就只说不存在。
     */
    default String missing(IdKind kind, String idOrTag) {
        String written = idOrTag.trim();
        boolean tag = written.startsWith("#");
        // 标签只和标签比、ID 只和 ID 比：#minecraft:log 该提示 #minecraft:logs，不该提示某一种原木。
        return SimilarIds.missing(tag ? kind.label() + "标签" : kind.label(), written,
                SimilarIds.closest(tag ? tags(kind) : ids(kind), written));
    }
}
